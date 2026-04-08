package com.photogrammetry.rendering

import android.util.SparseIntArray
import com.google.ar.core.PointCloud
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Accumulates ARCore 3D feature points across multiple frames with zero
 * per-point heap allocation.
 *
 * Design principles for large datasets
 * ─────────────────────────────────────
 * • Single flat FloatArray (x,y,z,conf interleaved) grows by doubling — one
 *   contiguous allocation, cache-friendly, no boxing.
 * • SparseIntArray (Int→Int, no boxing) maps ARCore point IDs to their slot
 *   index so refinements are O(log n) in-place writes rather than allocations.
 * • ReentrantReadWriteLock: update() holds the write lock (GL thread); snapshot()
 *   holds the read lock only for the duration of a bulk System.arraycopy.
 *
 * [update] must only be called from the GL thread.
 * [snapshot] and [size] are safe to call from any thread.
 */
class PointCloudAccumulator {

    companion object {
        private const val MIN_CONFIDENCE   = 0.2f
        private const val FLOATS_PER_POINT = 4          // x, y, z, confidence
        private const val INITIAL_SLOTS    = 4_096       // 64 KB initially
    }

    // id → slot index inside [buffer]
    private val idToSlot = SparseIntArray(INITIAL_SLOTS)

    // Flat interleaved buffer: [x0,y0,z0,c0, x1,y1,z1,c1, …]
    private var buffer = FloatArray(INITIAL_SLOTS * FLOATS_PER_POINT)

    @Volatile private var _size = 0
    // Internal mutable count — accessed only under write lock.
    private var count: Int
        get()      = _size
        set(value) { _size = value }

    private val lock = ReentrantReadWriteLock()

    /** Number of unique accumulated points. Lock-free volatile read. */
    val size: Int get() = _size

    // -------------------------------------------------------------------------
    // GL-thread writer
    // -------------------------------------------------------------------------

    fun update(pointCloud: PointCloud) {
        val pts = pointCloud.points   // FloatBuffer [x,y,z,conf, …] — ARCore-owned
        val ids = pointCloud.ids      // IntBuffer   [id, …]
        val n   = pointCloud.numPoints
        if (n == 0) return

        pts.rewind()
        ids.rewind()

        lock.write {
            ensureCapacity(count + n)

            repeat(n) { _ ->
                val arcoreId = ids.get()       // sequential read — no index multiply
                val x    = pts.get()           // sequential reads: x, y, z, conf
                val y    = pts.get()
                val z    = pts.get()
                val conf = pts.get()           // read conf once (was read twice before)
                if (conf < MIN_CONFIDENCE) return@repeat

                val slot    = idToSlot.get(arcoreId, -1)
                val dstBase = if (slot == -1) {
                    // New point — append to end
                    val newSlot = count
                    idToSlot.put(arcoreId, newSlot)
                    count++
                    newSlot * FLOATS_PER_POINT
                } else {
                    // Existing point — refine in-place (no allocation)
                    slot * FLOATS_PER_POINT
                }

                buffer[dstBase]     = x
                buffer[dstBase + 1] = y
                buffer[dstBase + 2] = z
                buffer[dstBase + 3] = conf
            }
        }
    }

    // -------------------------------------------------------------------------
    // Cross-thread reader
    // -------------------------------------------------------------------------

    /**
     * Returns a snapshot as a flat FloatArray [x0,y0,z0,c0, x1,…] and the
     * current point count. Uses a single bulk System.arraycopy under a read lock —
     * O(n) time, one allocation, no per-point boxing.
     */
    fun snapshot(): Pair<FloatArray, Int> = lock.read {
        val n    = count
        val copy = FloatArray(n * FLOATS_PER_POINT)
        System.arraycopy(buffer, 0, copy, 0, n * FLOATS_PER_POINT)
        Pair(copy, n)
    }

    /**
     * Zero-copy snapshot into a pre-allocated destination array.
     * @param dst    destination FloatArray
     * @param offset starting index in dst (in floats, not points)
     * @return number of points copied
     */
    fun snapshotInto(dst: FloatArray, offset: Int = 0): Int = lock.read {
        val n = count
        System.arraycopy(buffer, 0, dst, offset, n * FLOATS_PER_POINT)
        n
    }

    fun clear() = lock.write {
        idToSlot.clear()
        count = 0
        // Retain the current buffer capacity — avoids re-allocation on next scan
    }

    /**
     * Releases excess buffer memory after a scan is cleared.
     * Halves the internal buffer when actual usage is below 25% of capacity.
     * Should be called after [clear] when no further scanning is expected soon
     * (e.g. when the user exports and navigates away).
     */
    fun trimToSize() = lock.write {
        val used = _size * FLOATS_PER_POINT
        val cap  = buffer.size
        // Shrink if usage < 25% of capacity, but never below INITIAL_SLOTS
        if (used < cap / 4 && cap > INITIAL_SLOTS * FLOATS_PER_POINT) {
            val newCap = maxOf(INITIAL_SLOTS * FLOATS_PER_POINT, used * 2)
            buffer = buffer.copyOf(newCap)
        }
    }

    // -------------------------------------------------------------------------
    // Growth
    // -------------------------------------------------------------------------

    /** Must be called inside the write lock. */
    private fun ensureCapacity(neededSlots: Int) {
        val currentSlots = buffer.size / FLOATS_PER_POINT
        if (neededSlots <= currentSlots) return
        var newSlots = currentSlots
        while (newSlots < neededSlots) newSlots = newSlots shl 1   // double
        buffer = buffer.copyOf(newSlots * FLOATS_PER_POINT)
    }
}

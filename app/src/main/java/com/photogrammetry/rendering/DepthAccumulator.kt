package com.photogrammetry.rendering

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Thread-safe accumulator for depth-image back-projected world-space points.
 *
 * Improvement #5 — adaptive near/far voxel routing:
 *   Points closer than [NEAR_THRESHOLD_M] are stored in a 5 mm grid, matching
 *   the Pixel 9 Pro depth sensor's resolution at close range (~5 mm accuracy).
 *   Points at or beyond [NEAR_THRESHOLD_M] use a 2 cm grid, matching sensor
 *   accuracy at >1 m range.  Using a single 1 cm grid wastes either memory
 *   (near range is over-quantised) or precision (far range is under-quantised).
 *
 * Improvement #17 — exportMerged():
 *   Concatenates near + far grid snapshots into a single output buffer for
 *   export, without holding both grids locked simultaneously.
 *
 * Total expected capacity at full room scan:
 *   near: ~50 000 points  (1 m³ at 5 mm resolution)
 *   far:  ~270 000 points (30 m² × 3 m at 2 cm resolution)
 */
class DepthAccumulator {

    companion object {
        private const val NEAR_THRESHOLD_M  = 1.0f    // < 1 m → 5 mm grid
        private const val NEAR_VOXEL_SIZE   = 0.005f  // 5 mm
        private const val FAR_VOXEL_SIZE    = 0.02f   // 2 cm
        // Squared threshold avoids sqrt() on every point in insertBatch()
        private const val NEAR_THRESHOLD_SQ = NEAR_THRESHOLD_M * NEAR_THRESHOLD_M
        /** Maximum accumulated depth points (~25 MB at 4 floats/pt). */
        const val MAX_POINTS = 800_000
    }

    private val nearGrid = VoxelGrid(NEAR_VOXEL_SIZE)
    private val farGrid  = VoxelGrid(FAR_VOXEL_SIZE)
    private val lock     = ReentrantReadWriteLock()
    @Volatile private var _size = 0

    /** Total unique depth points in near + far grids.  Lock-free volatile read. */
    val size: Int get() = _size

    /**
     * Batch-inserts [count] world-space points from [buf] starting at [offset].
     * Buffer layout: [x, y, z, confidence_0_1, x, y, z, …]
     *
     * Points are routed to the near or far grid based on Euclidean distance
     * from world origin: distance < [NEAR_THRESHOLD_M] → near grid (5 mm).
     *
     * Must only be called from the GL thread.
     */
    fun insertBatch(buf: FloatArray, count: Int, offset: Int = 0) {
        if (count == 0) return
        if (_size >= MAX_POINTS) return   // cap reached — drop new depth data
        lock.write {
            var i = offset
            val end = offset + count * 4
            while (i < end) {
                val x = buf[i]; val y = buf[i+1]; val z = buf[i+2]; val c = buf[i+3]
                // Compare squared distance to avoid sqrt() on every point
                if (x*x + y*y + z*z < NEAR_THRESHOLD_SQ) {
                    nearGrid.insert(x, y, z, c)
                } else {
                    farGrid.insert(x, y, z, c)
                }
                i += 4
            }
            _size = nearGrid.size + farGrid.size
        }
    }

    /**
     * Returns a snapshot of just the near-field points.
     * Safe to call from any thread.
     */
    fun snapshotNear(): Pair<FloatArray, Int> = lock.read { nearGrid.snapshot() }

    /**
     * Returns a snapshot of just the far-field points.
     * Safe to call from any thread.
     */
    fun snapshotFar(): Pair<FloatArray, Int> = lock.read { farGrid.snapshot() }

    /**
     * Returns a single merged flat [FloatArray] snapshot combining both grids.
     * Uses [VoxelGrid.snapshotInto] to write both grids directly into one
     * allocation — no intermediate arrays.
     */
    fun exportMerged(): Pair<FloatArray, Int> = lock.read {
        val total = nearGrid.size + farGrid.size
        val out   = FloatArray(total * 4)
        val nNear = nearGrid.snapshotInto(out, 0)
        farGrid.snapshotInto(out, nNear * 4)
        Pair(out, total)
    }

    /**
     * Zero-copy variant: writes near + far grid data directly into [dst] starting
     * at float index [offset].  No intermediate FloatArray allocated.
     * Returns the number of points written (== [size]).
     * Caller must ensure dst has at least ([size] * 4 + offset) capacity.
     */
    fun exportMergedInto(dst: FloatArray, offset: Int = 0): Int = lock.read {
        val nNear = nearGrid.snapshotInto(dst, offset)
        val nFar  = farGrid.snapshotInto(dst, offset + nNear * 4)
        nNear + nFar
    }

    /** Legacy compatibility: returns merged snapshot. */
    fun snapshot(): Pair<FloatArray, Int> = exportMerged()

    fun clear() = lock.write { nearGrid.clear(); farGrid.clear(); _size = 0 }
}

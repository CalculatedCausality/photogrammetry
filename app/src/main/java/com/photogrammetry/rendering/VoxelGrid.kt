package com.photogrammetry.rendering

import kotlin.math.floor

/**
 * Memory-efficient spatial hash grid backed by an open-addressing flat array
 * hash table that eliminates all per-voxel heap allocations.
 *
 * Previous HashMap<Long, FloatArray> allocated one FloatArray(4) per new voxel.
 * A typical room scan at 1 cm resolution produces ~270 000 voxels causing
 * sustained GC pressure. The flat parallel-array design eliminates these objects.
 *
 * Design:
 *   keys   LongArray  — voxel keys, sentinel = Long.MIN_VALUE
 *   values FloatArray — flat [x,y,z,conf] * capacity
 *
 * Open-addressing with quadratic probing, rehashes at 55% load factor.
 *
 * AABB crop: call setCropBox() to restrict insertions to a region of interest.
 *
 * NOT thread-safe — wrap in DepthAccumulator for cross-thread access.
 */
class VoxelGrid(val voxelSize: Float = 0.01f) {

    companion object {
        private const val EMPTY            = Long.MIN_VALUE
        private const val LOAD_FACTOR_MAX  = 0.55f
        private const val INITIAL_CAPACITY = 65_536  // power-of-2
    }

    private var capacity   = INITIAL_CAPACITY
    private var mask       = capacity - 1
    private var keys       = LongArray(capacity) { EMPTY }
    private var values     = FloatArray(capacity * 4)
    private var occupancy  = 0
    private var slotList   = IntArray(capacity)   // dense occupied-slot indices → O(n) snapshot

    private var cropActive = false
    private var cropMinX = 0f; private var cropMaxX = 0f
    private var cropMinY = 0f; private var cropMaxY = 0f
    private var cropMinZ = 0f; private var cropMaxZ = 0f

    val size: Int get() = occupancy

    fun setCropBox(minX: Float, minY: Float, minZ: Float,
                   maxX: Float, maxY: Float, maxZ: Float) {
        cropMinX = minX; cropMaxX = maxX
        cropMinY = minY; cropMaxY = maxY
        cropMinZ = minZ; cropMaxZ = maxZ
        cropActive = true
    }

    fun clearCropBox() { cropActive = false }

    fun insert(x: Float, y: Float, z: Float, conf: Float) {
        if (cropActive &&
            (x < cropMinX || x > cropMaxX ||
             y < cropMinY || y > cropMaxY ||
             z < cropMinZ || z > cropMaxZ)) return

        if (occupancy >= (capacity * LOAD_FACTOR_MAX).toInt()) rehash()

        val key  = key(x, y, z)
        var slot = hashMix(key) and mask
        var step = 0
        while (true) {
            val k = keys[slot]
            if (k == EMPTY) {
                keys[slot] = key
                val base = slot * 4
                values[base] = x; values[base+1] = y
                values[base+2] = z; values[base+3] = conf
                slotList[occupancy] = slot
                occupancy++
                return
            }
            if (k == key) {
                val base = slot * 4
                if (conf > values[base + 3]) {
                    values[base] = x; values[base+1] = y
                    values[base+2] = z; values[base+3] = conf
                }
                return
            }
            step++
            slot = (slot + step) and mask
        }
    }

    fun insertBatch(buf: FloatArray, count: Int, offset: Int = 0) {
        if (cropActive) {
            // Crop-enabled path: delegate to insert() which checks each point
            var i = offset
            val end = offset + count * 4
            while (i < end) {
                insert(buf[i], buf[i+1], buf[i+2], buf[i+3])
                i += 4
            }
        } else {
            // No-crop fast-path: inline insert without the per-point crop branch
            var i = offset
            val end = offset + count * 4
            val threshold = (capacity * LOAD_FACTOR_MAX).toInt()
            while (i < end) {
                if (occupancy >= threshold) { rehash(); /* threshold updated below */ }
                val x = buf[i]; val y = buf[i+1]; val z = buf[i+2]; val conf = buf[i+3]
                val key  = key(x, y, z)
                var slot = hashMix(key) and mask
                var step = 0
                while (true) {
                    val k = keys[slot]
                    if (k == EMPTY) {
                        keys[slot] = key
                        val base = slot * 4
                        values[base] = x; values[base+1] = y
                        values[base+2] = z; values[base+3] = conf
                        slotList[occupancy] = slot
                        occupancy++
                        break
                    }
                    if (k == key) {
                        val base = slot * 4
                        if (conf > values[base + 3]) {
                            values[base] = x; values[base+1] = y
                            values[base+2] = z; values[base+3] = conf
                        }
                        break
                    }
                    step++
                    slot = (slot + step) and mask
                }
                i += 4
            }
        }
    }

    fun snapshot(): Pair<FloatArray, Int> {
        val n   = occupancy
        val out = FloatArray(n * 4)
        snapshotInto(out, 0)
        return Pair(out, n)
    }

    /**
     * Writes occupancy data into [dst] starting at float index [dstOffset].
     * Returns the number of points written (== [size]).
     * Zero-alloc: no FloatArray allocated; caller owns the destination buffer.
     */
    fun snapshotInto(dst: FloatArray, dstOffset: Int = 0): Int {
        var d = dstOffset
        for (i in 0 until occupancy) {
            val src = slotList[i] * 4
            dst[d] = values[src]; dst[d+1] = values[src+1]
            dst[d+2] = values[src+2]; dst[d+3] = values[src+3]
            d += 4
        }
        return occupancy
    }

    fun clear() {
        keys.fill(EMPTY)
        occupancy = 0
    }

    private fun rehash() {
        val oldCap    = capacity
        val oldKeys   = keys
        val oldValues = values

        capacity = oldCap shl 1
        mask     = capacity - 1
        keys     = LongArray(capacity) { EMPTY }
        values   = FloatArray(capacity * 4)
        slotList = IntArray(capacity)
        occupancy = 0

        for (slot in 0 until oldCap) {
            val k = oldKeys[slot]
            if (k == EMPTY) continue
            val src = slot * 4
            insertRaw(k, oldValues[src], oldValues[src+1], oldValues[src+2], oldValues[src+3])
        }
    }

    private fun insertRaw(key: Long, x: Float, y: Float, z: Float, conf: Float) {
        var slot = hashMix(key) and mask
        var step = 0
        while (true) {
            if (keys[slot] == EMPTY) {
                keys[slot] = key
                val base = slot * 4
                values[base]=x; values[base+1]=y; values[base+2]=z; values[base+3]=conf
                slotList[occupancy] = slot
                occupancy++
                return
            }
            step++
            slot = (slot + step) and mask
        }
    }

    /** Murmur-style 64-bit finalizer — spreads key bits across probe-start position. */
    private fun hashMix(h: Long): Int {
        var x = h xor (h ushr 33)
        x *= -49064778989728563L   // 0xFF51AFD7ED558CCDuL
        x = x xor (x ushr 33)
        x *= -4265267296055464877L  // 0xC4CEB9FE1A85EC53uL
        x = x xor (x ushr 33)
        return x.toInt()
    }

    private fun key(x: Float, y: Float, z: Float): Long {
        val ix = floor(x / voxelSize).toInt()
        val iy = floor(y / voxelSize).toInt()
        val iz = floor(z / voxelSize).toInt()
        return ((ix.toLong() and 0x1FFFFF) shl 42) or
               ((iy.toLong() and 0x1FFFFF) shl 21) or
                (iz.toLong() and 0x1FFFFF)
    }
}
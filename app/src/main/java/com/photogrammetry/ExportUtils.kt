package com.photogrammetry

/**
 * Shared export utilities used by [PlyExporter], [StlExporter], and [ObjExporter].
 */

/**
 * Returns [minX, minY, minZ, maxX, maxY, maxZ] of the point cloud.
 * @param buf   flat interleaved FloatArray [x, y, z, conf, …]
 * @param count number of valid points
 */
internal fun computeAabb(buf: FloatArray, count: Int): FloatArray {
    if (count == 0) return floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)
    var minX = buf[0]; var minY = buf[1]; var minZ = buf[2]
    var maxX = minX;   var maxY = minY;   var maxZ = minZ
    var i = 0
    while (i < count * 4) {
        val x = buf[i]; val y = buf[i + 1]; val z = buf[i + 2]
        if (x < minX) minX = x; if (x > maxX) maxX = x
        if (y < minY) minY = y; if (y > maxY) maxY = y
        if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
        i += 4
    }
    return floatArrayOf(minX, minY, minZ, maxX, maxY, maxZ)
}

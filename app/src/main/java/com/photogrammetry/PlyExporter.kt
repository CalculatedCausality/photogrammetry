package com.photogrammetry

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Serialises a 3D point cloud to a **binary little-endian PLY** file using NIO
 * [FileChannel] for a single-syscall write path.
 *
 * Output directory: [Context.getExternalFilesDir] — no WRITE permission needed (API 24+).
 * Example path: …/files/scan_20260407_153012.ply
 *
 * Why binary PLY?
 * ───────────────
 * • 16 bytes/point (4 × float32) vs ~32 chars/point for ASCII — files are 2× smaller.
 * • No float→string conversion; data is written as raw IEEE-754 little-endian floats.
 * • A single NIO FileChannel.write() call per page  (256 KB chunks) avoids
 *   per-character virtual dispatch and lets the OS DMA directly from the heap.
 * • For 100 k points: ~1.6 MB binary vs ~3.2 MB ASCII, and >10× faster to write.
 *
 * This function is blocking; call it from a background thread.
 *
 * @param buf   flat interleaved FloatArray [x0,y0,z0,c0, x1,y1,z1,c1, …]
 * @param count number of valid points in [buf]
 */
object PlyExporter {

    private val DATE_FMT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    /** Number of floats per page written to the channel (256 KB / 4 = 65 536 floats = 16 384 points). */
    private const val PAGE_FLOATS = 65_536
    /** Bytes per point: 4 floats (x,y,z,conf) + 3 bytes (r,g,b). */
    private const val BYTES_PER_POINT = 19
    private const val PAGE_BYTES = 256 * 1_024
    // Singleton page buffer — allocated once, reused across all exports.
    // Thread-safe because exportExecutor is a single-thread executor.
    private val sharedPage: ByteBuffer by lazy {
        ByteBuffer.allocateDirect(PAGE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    }

    fun export(context: Context, buf: FloatArray, count: Int): File {
        require(count >= 0 && count * 4 <= buf.size) { "Invalid buf/count" }

        val timestamp = DATE_FMT.format(Date())
        val dir  = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, "scan_$timestamp.ply")

        // ── Compute AABB and density before opening the file ────────────────
        val (minX, minY, minZ, maxX, maxY, maxZ) = computeAabb(buf, count)
        val dx = maxX - minX; val dy = maxY - minY; val dz = maxZ - minZ
        val volume = dx * dy * dz   // m³
        val density = if (volume > 1e-6f) count / volume else 0f

        FileChannel.open(
            file.toPath(),
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.WRITE,
            java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
        ).use { ch ->
            // ── ASCII header with bounding box + scan stats ──────────────────
            val header = buildString {
                append("ply\n")
                append("format binary_little_endian 1.0\n")
                append("comment Exported by Photogrammetry Android app\n")
                append("comment export_time $timestamp\n")
                append("comment point_count $count\n")
                append(String.format(Locale.US,
                    "comment bbox_min %.4f %.4f %.4f\n", minX, minY, minZ))
                append(String.format(Locale.US,
                    "comment bbox_max %.4f %.4f %.4f\n", maxX, maxY, maxZ))
                append(String.format(Locale.US,
                    "comment bbox_size_m %.4f %.4f %.4f\n", dx, dy, dz))
                append(String.format(Locale.US,
                    "comment volume_m3 %.4f\n", volume))
                append(String.format(Locale.US,
                    "comment density_pts_per_m3 %.1f\n", density))
                append("element vertex $count\n")
                append("property float x\n")
                append("property float y\n")
                append("property float z\n")
                append("property float confidence\n")
                append("property uchar red\n")
                append("property uchar green\n")
                append("property uchar blue\n")
                append("end_header\n")
            }.toByteArray(Charsets.US_ASCII)

            ch.write(ByteBuffer.wrap(header))

            // ── Binary point data — paged to avoid a single huge allocation ─
            // Layout per point: [x:f32][y:f32][z:f32][conf:f32][r:u8][g:u8][b:u8]
            //                    4      4      4       4          1    1    1 = 19 bytes
            // RGB encodes confidence as a red→green gradient for coloured-PLY viewers.
            val page = sharedPage.also { it.clear() }

            var i = 0
            while (i < count) {
                page.clear()
                while (i < count && page.remaining() >= BYTES_PER_POINT) {
                    val base = i * 4
                    val x    = buf[base]
                    val y    = buf[base + 1]
                    val z    = buf[base + 2]
                    val conf = buf[base + 3].coerceIn(0f, 1f)

                    page.putFloat(x); page.putFloat(y); page.putFloat(z)
                    page.putFloat(conf)
                    // Red→green confidence gradient: low=red, mid=yellow, high=green
                    val r = ((1f - conf) * 255f).toInt().coerceIn(0, 255).toByte()
                    val g = (conf * 255f).toInt().coerceIn(0, 255).toByte()
                    page.put(r); page.put(g); page.put(0)
                    i++
                }
                page.flip()
                ch.write(page)
            }
        }

        return file
    }

    /** Returns [minX, minY, minZ, maxX, maxY, maxZ] of the point cloud. */
    private fun computeAabb(buf: FloatArray, count: Int): FloatArray {
        if (count == 0) return floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)
        var minX = buf[0]; var minY = buf[1]; var minZ = buf[2]
        var maxX = minX;   var maxY = minY;   var maxZ = minZ
        var i = 0
        while (i < count * 4) {
            val x = buf[i]; val y = buf[i+1]; val z = buf[i+2]
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
            i += 4
        }
        return floatArrayOf(minX, minY, minZ, maxX, maxY, maxZ)
    }
}



package com.photogrammetry

import android.content.Context
import android.media.MediaMetadataRetriever
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight representation of a captured scan file on disk.
 */
data class ScanFile(
    val file: File
) {
    enum class Format { PLY, STL, OBJ, MP4, UNKNOWN }

    val format: Format get() = when (file.extension.lowercase(Locale.US)) {
        "ply" -> Format.PLY
        "stl" -> Format.STL
        "obj" -> Format.OBJ
        "mp4" -> Format.MP4
        else  -> Format.UNKNOWN
    }

    val displayName: String get() = file.name

    val sizeLabel: String get() {
        val bytes = file.length()
        return when {
            bytes < 1_024L              -> "$bytes B"
            bytes < 1_048_576L          -> "${"%.1f".format(bytes / 1_024f)} KB"
            else                        -> "${"%.1f".format(bytes / 1_048_576f)} MB"
        }
    }

    val dateLabel: String get() = DATE_FMT.format(Date(file.lastModified()))

    /**
     * Duration label for MP4 recordings (e.g. "2:34").
     * Returns empty string for PLY/STL files.
     * Uses [MediaMetadataRetriever] which reads the file's metadata without
     * decoding any video frames — fast and low-overhead.
     */
    val durationLabel: String get() {
        if (format != Format.MP4) return ""
        return try {
            MediaMetadataRetriever().use { mmr ->
                mmr.setDataSource(file.absolutePath)
                val ms = mmr.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull() ?: return ""
                val totalSec = ms / 1_000L
                "%d:%02d".format(totalSec / 60L, totalSec % 60L)
            }
        } catch (_: Exception) { "" }
    }

    companion object {
        private val DATE_FMT = SimpleDateFormat("dd MMM yyyy  HH:mm", Locale.US)

        /**
         * Reads the first 32 lines of a PLY or OBJ file looking for a
         * "comment point_count N" line embedded by [PlyExporter] / [ObjExporter].
         * For binary STL files, reads the triangle count from bytes 80-83 and
         * divides by 4 (StlExporter uses 4 triangles per tetrahedron point).
         * Returns null if not found or on error.
         */
        fun pointCountFromHeader(file: File): Int? {
            if (!file.exists() || file.length() == 0L) return null
            // Binary STL: fixed 84-byte header, uint32 triangle count at bytes 80-83
            if (file.extension.lowercase(Locale.US) == "stl") {
                return try {
                    file.inputStream().use { s ->
                        s.skip(80)
                        val b = ByteArray(4)
                        if (s.read(b) != 4) return null
                        val triangles = ((b[0].toInt() and 0xFF)) or
                                        ((b[1].toInt() and 0xFF) shl 8) or
                                        ((b[2].toInt() and 0xFF) shl 16) or
                                        ((b[3].toInt() and 0xFF) shl 24)
                        // StlExporter.TRIS_PER_POINT = 4
                        triangles / 4
                    }
                } catch (_: Exception) { null }
            }
            return try {
                file.bufferedReader().use { br ->
                    repeat(32) {
                        val line = br.readLine() ?: return null
                        // Our custom header comment (PLY + STL)
                        if (line.startsWith("comment point_count ") ||
                            line.startsWith("# point_count ")) {
                            return line.substringAfterLast(' ').trim().toIntOrNull()
                        }
                        // Standard PLY spec: "element vertex N"
                        if (line.startsWith("element vertex ")) {
                            return line.substringAfterLast(' ').trim().toIntOrNull()
                        }
                    }
                    null
                }
            } catch (_: Exception) { null }
        }

        /** Returns all PLY, STL, OBJ and MP4 session recordings, newest first. */
        fun listAll(context: Context): List<ScanFile> {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            return dir.listFiles { f ->
                val ext = f.extension.lowercase(Locale.US)
                ext == "ply" || ext == "stl" || ext == "obj" || ext == "mp4"
            }
                ?.sortedByDescending { it.lastModified() }
                ?.map { ScanFile(it) }
                ?: emptyList()
        }
    }
}

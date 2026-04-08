package com.photogrammetry

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.ar.core.RecordingConfig
import com.google.ar.core.RecordingStatus
import com.google.ar.core.Session
import com.google.ar.core.TrackData
import com.google.ar.core.exceptions.RecordingFailedException
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Wraps ARCore's [Session] Recording API to save complete AR sessions as MP4
 * files on the Pixel 9 Pro.
 *
 * Why ARCore recording?
 * ─────────────────────
 * ARCore recordings capture the raw camera frames, IMU sensor data, and pose
 * estimates in a standard MP4 container.  Recordings can be played back in
 * ARCore (via [Session.setPlaybackDatasetUri]) allowing:
 *   • Offline re-processing without the physical environment
 *   • Higher-quality post-processing (Meshroom, COLMAP, NeRF) on desktop
 *   • Sharing raw scan sessions with collaborators
 *
 * Pixel 9 Pro specifics
 * ──────────────────────
 * The Tensor G4's Video Boost co-processor is active during camera capture,
 * so recorded frames already have Google's HDR+ tone mapping applied.
 * The Pixel 9 Pro's ISP also writes full-resolution frames to ARCore even
 * during 60 fps AR sessions — other devices often downsample at high FPS.
 *
 * Output: [Context.getExternalFilesDir]/session_YYYYMMDD_HHmmss.mp4
 */
object SessionRecorder {

    private const val TAG = "SessionRecorder"
    private val DATE_FMT  = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    /**
     * Improvement #12 — custom ARCore data track for embedded point cloud stats.
     *
     * This UUID uniquely identifies the "photogrammetry_stats" track written into
     * every recorded MP4.  Playback tools or post-processing scripts can look for
     * this track to recover the feature + depth point counts at each recorded frame.
     *
     * Track data format (12 bytes, little-endian):
     *   [0..3]  Int32  feature point count
     *   [4..7]  Int32  depth point count
     *   [8..11] Int32  total point count (feature + depth)
     */
    val POINT_STATS_TRACK_ID: UUID = UUID.fromString("a3f1c2b4-d5e6-7890-abcd-ef1234567890")

    private val trackDataBuf: ByteBuffer =
        ByteBuffer.allocateDirect(12).order(ByteOrder.LITTLE_ENDIAN)

    /** The file currently being recorded to, or null if not recording. */
    var currentRecordingFile: File? = null
        private set

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    val isRecording: Boolean
        get() = currentRecordingFile != null

    /**
     * Starts recording the AR session to a new MP4 file.
     * @return the output [File], or null if recording could not be started.
     */
    fun start(context: Context, session: Session): File? {
        if (isRecording) {
            Log.w(TAG, "Already recording — ignoring start()")
            return currentRecordingFile
        }

        val timestamp = DATE_FMT.format(Date())
        val dir  = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, "session_$timestamp.mp4")

        return try {
            val trackData = TrackData.builder()
                .setId(POINT_STATS_TRACK_ID)
                .setMimeType("application/vnd.photogrammetry.point_stats")
                .build()

            val config = RecordingConfig(session)
                .setMp4DatasetUri(Uri.fromFile(file))
                .setAutoStopOnPause(false)
                .addTrack(trackData)

            session.startRecording(config)
            currentRecordingFile = file
            Log.i(TAG, "Recording started → ${file.name}")
            file
        } catch (e: RecordingFailedException) {
            Log.e(TAG, "Failed to start recording: ${e.message}")
            null
        }
    }

    /**
     * Stops an in-progress recording.
     * @return the completed [File], or null if nothing was recording.
     */
    fun stop(session: Session): File? {
        val file = currentRecordingFile ?: return null

        return try {
            if (session.recordingStatus == RecordingStatus.OK) {
                session.stopRecording()
            }
            Log.i(TAG, "Recording stopped → ${file.name}")
            currentRecordingFile = null
            file
        } catch (e: RecordingFailedException) {
            Log.e(TAG, "Failed to stop recording: ${e.message}")
            currentRecordingFile = null
            null
        }
    }

    /**
     * Writes current point cloud counts into the custom data track.
     *
     * Call this from the GL thread (inside [onDrawFrame]) every N frames while
     * recording.  The 12-byte payload is embedded as a [POINT_STATS_TRACK_ID]
     * track entry in the MP4 file at the current timestamp.
     */
    fun recordPointStats(session: Session, featureCount: Int, depthCount: Int) {
        if (!isRecording || session.recordingStatus != RecordingStatus.OK) return
        trackDataBuf.clear()
        trackDataBuf.putInt(featureCount)
        trackDataBuf.putInt(depthCount)
        trackDataBuf.putInt(featureCount + depthCount)
        trackDataBuf.rewind()
        try {
            session.recordTrackData(POINT_STATS_TRACK_ID, trackDataBuf)
        } catch (e: Exception) {
            Log.w(TAG, "recordTrackData failed: ${e.message}")
        }
    }
}

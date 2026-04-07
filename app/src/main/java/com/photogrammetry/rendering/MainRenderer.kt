package com.photogrammetry.rendering

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.photogrammetry.MainActivity
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Central GL renderer. Coordinates the ARCore session, camera background, and point cloud.
 *
 * @param onStatusUpdate Called from the GL thread; implementations must post to the UI thread.
 */
class MainRenderer(
    private val context: Context,
    private val onStatusUpdate: (
        trackingState: String,
        failureReason: String,
        pointCount: Int,
        fps: Float
    ) -> Unit
) : GLSurfaceView.Renderer {

    companion object {
        private const val TAG = "MainRenderer"
        private const val NEAR_CLIP = 0.1f
        private const val FAR_CLIP  = 100.0f
    }

    // Session is set from the main thread; reads happen on the GL thread.
    private val sessionRef = AtomicReference<Session?>(null)

    private val backgroundRenderer  = BackgroundRenderer()
    private val pointCloudRenderer  = PointCloudRenderer()

    // Display geometry — written on GL thread from onSurfaceChanged
    @Volatile private var displayWidth   = 0
    @Volatile private var displayHeight  = 0

    // FPS counters — only accessed from the GL thread
    private var fpsFrameCount = 0
    private var fpsWindowStart = System.currentTimeMillis()
    private var fps = 0f

    fun setSession(session: Session) = sessionRef.set(session)

    // -------------------------------------------------------------------------
    // GLSurfaceView.Renderer
    // -------------------------------------------------------------------------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        try {
            backgroundRenderer.createOnGlThread(context)
            pointCloudRenderer.createOnGlThread(context)
        } catch (e: Exception) {
            Log.e(TAG, "Renderer init failed", e)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        displayWidth  = width
        displayHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        val session = sessionRef.get() ?: return

        // Inform ARCore of current display geometry (rotation + size) before update.
        val rotation = (context as? MainActivity)?.getDisplayRotation() ?: 0
        session.setDisplayGeometry(rotation, displayWidth, displayHeight)

        // Attach the OES texture so ARCore can write the camera feed into it.
        session.setCameraTextureName(backgroundRenderer.textureId)

        val frame = try {
            session.update()
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available during update", e)
            return
        }

        // Always draw the camera background regardless of tracking state.
        backgroundRenderer.draw(frame)

        val camera = frame.camera
        val trackingState = camera.trackingState

        updateFps()

        var pointCount = 0

        if (trackingState == TrackingState.TRACKING) {
            val projMatrix = FloatArray(16)
            camera.getProjectionMatrix(projMatrix, 0, NEAR_CLIP, FAR_CLIP)

            val viewMatrix = FloatArray(16)
            camera.getViewMatrix(viewMatrix, 0)

            frame.acquirePointCloud().use { pointCloud ->
                pointCount = pointCloud.numPoints
                if (pointCount > 0) {
                    pointCloudRenderer.update(pointCloud)
                    pointCloudRenderer.draw(viewMatrix, projMatrix)
                }
            }
        }

        val stateLabel = when (trackingState) {
            TrackingState.TRACKING -> "Tracking"
            TrackingState.PAUSED   -> "Paused"
            TrackingState.STOPPED  -> "Stopped"
            else                   -> "Unknown"
        }

        val failureLabel = if (trackingState == TrackingState.PAUSED) {
            when (camera.trackingFailureReason) {
                TrackingFailureReason.INSUFFICIENT_LIGHT    -> "Low light"
                TrackingFailureReason.EXCESSIVE_MOTION      -> "Move slower"
                TrackingFailureReason.INSUFFICIENT_FEATURES -> "Point camera at textured surface"
                TrackingFailureReason.CAMERA_UNAVAILABLE    -> "Camera unavailable"
                else -> ""
            }
        } else ""

        onStatusUpdate(stateLabel, failureLabel, pointCount, fps)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun updateFps() {
        fpsFrameCount++
        val now     = System.currentTimeMillis()
        val elapsed = now - fpsWindowStart
        if (elapsed >= 1000L) {
            fps = fpsFrameCount * 1000f / elapsed
            fpsFrameCount  = 0
            fpsWindowStart = now
        }
    }
}

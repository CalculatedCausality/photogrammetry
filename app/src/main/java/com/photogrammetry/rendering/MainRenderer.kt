package com.photogrammetry.rendering

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.google.ar.core.Frame
import android.util.Log
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.photogrammetry.MainActivity
import com.photogrammetry.PerformanceOptimizer
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Central OpenGL ES renderer.
 *
 * Pixel 9 Pro additions vs base implementation:
 * • [PerformanceOptimizer] — reports per-frame work duration to the Tensor G4
 *   scheduler so it pre-warms the correct CPU cluster before the next frame.
 * • [DepthAccumulator] + [DepthProjector] — back-projects DEPTH16 pixels into
 *   world-space points on every accumulating frame.  On the Pixel 9 Pro's 180×160
 *   depth image this contributes up to ~10 000 additional points per frame
 *   (after voxel dedup).  Combined with ARCore feature points the total
 *   accumulated cloud is 10–60× denser than features alone.
 * • Pre-allocated scratch buffer ([depthScratch]) for the depth back-projection
 *   temp points — zero GL-thread heap allocation per frame.
 */
class MainRenderer(
    private val context: Context,
    private val onStatusUpdate: (
        trackingState: String,
        failureReason: String,
        pointCount: Int,
        accumCount: Int,
        fps: Float
    ) -> Unit
) : GLSurfaceView.Renderer {

    companion object {
        private const val TAG        = "MainRenderer"
        private const val NEAR_CLIP  = 0.1f
        private const val FAR_CLIP   = 100.0f
        // Max depth pixels projected per frame (180×160 = 28 800; keep headroom)
        private const val DEPTH_SCRATCH_POINTS = 30_000
    }

    private val sessionRef = AtomicReference<Session?>(null)

    private val backgroundRenderer   = BackgroundRenderer()
    private val pointCloudRenderer  = PointCloudRenderer()
    private val depthRenderer       = DepthRenderer()
    private val accumulatedRenderer = AccumulatedRenderer()

    /** Feature-point accumulator: ARCore-ID-keyed, lock-protected flat array. */
    val accumulator      = PointCloudAccumulator()

    /** Depth-image accumulator: near (5 mm) / far (2 cm) adaptive voxel routing. */
    val depthAccumulator = DepthAccumulator()

    /** Performance Hint session for the Tensor G4 scheduler. */
    val performanceOptimizer = PerformanceOptimizer(context)

    @Volatile var isAccumulating        = false
    @Volatile var isDepthOverlayEnabled = false
    /** Depth image sampling stride — increased by ThermalManager at high heat. */
    @Volatile var depthStride: Int = 2
    @Volatile private var depthSupported = false
    // Batch-dirty threshold: flush AccumulatedRenderer every N new depth points.
    // Exposed as a mutable volatile so ThermalManager can increase it under heat.
    @Volatile var dirtyBatchSize: Int = 5_000

    /**
     * Callback invoked on the GL thread once per frame, just after [Frame] is
     * acquired and before it is released by the next [session.update] call.
     * Use this instead of retaining the Frame reference across frames.
     */
    var onFrameReady: ((Frame) -> Unit)? = null
    private var lastDirtyDepthSize = 0
    // Pre-allocated GL-thread merge buffer.  Grown on demand, never shrunk.
    // Safe to pass directly to markDirty: AccumulatedRenderer.draw() consumes it
    // atomically within the same onDrawFrame call (both are on the GL thread).
    private var mergedBuf = FloatArray(0)

    @Volatile private var displayWidth  = 0
    @Volatile private var displayHeight = 0

    // GL-thread-only scratch: pre-allocated for depth back-projection output.
    // Avoids any heap allocation inside onDrawFrame.
    private val depthScratch = FloatArray(DEPTH_SCRATCH_POINTS * 4)

    // Reusable GL-thread matrix scratch arrays
    private val projScratch = FloatArray(16)
    private val viewScratch = FloatArray(16)

    // FPS tracking (GL thread only)
    private var fpsFrameCount  = 0
    private var fpsWindowStart = System.currentTimeMillis()
    private var fps            = 0f

    // -------------------------------------------------------------------------
    // Session management
    // -------------------------------------------------------------------------

    fun setSession(session: Session) {
        depthSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        sessionRef.set(session)
    }

    // -------------------------------------------------------------------------
    // GLSurfaceView.Renderer
    // -------------------------------------------------------------------------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        // In OpenGL ES 3.0, gl_PointSize in vertex shaders is always active —
        // no glEnable(GL_PROGRAM_POINT_SIZE) call is needed (that's desktop GL only).
        try {
            backgroundRenderer.createOnGlThread(context)
            pointCloudRenderer.createOnGlThread(context)
            depthRenderer.createOnGlThread(context)
            accumulatedRenderer.createOnGlThread(context)
        } catch (e: Exception) {
            Log.e(TAG, "Renderer init failed", e)
        }

        // Register GL thread with the Tensor G4 performance hint scheduler.
        // Must be called here (GL thread) so Process.myTid() returns the right TID.
        performanceOptimizer.initialize()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        displayWidth  = width
        displayHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {
        // ── Performance hint: frame work START ────────────────────────────
        performanceOptimizer.frameStart()

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        val session = sessionRef.get() ?: run {
            performanceOptimizer.frameEnd()
            return
        }

        val rotation = (context as? MainActivity)?.getDisplayRotation() ?: 0
        session.setDisplayGeometry(rotation, displayWidth, displayHeight)
        session.setCameraTextureName(backgroundRenderer.textureId)

        val frame = try {
            session.update()
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available", e)
            performanceOptimizer.frameEnd()
            return
        }

        onFrameReady?.invoke(frame)
        backgroundRenderer.draw(frame)

        val camera        = frame.camera
        val trackingState = camera.trackingState

        updateFps()

        var livePointCount = 0

        if (trackingState == TrackingState.TRACKING) {
            camera.getProjectionMatrix(projScratch, 0, NEAR_CLIP, FAR_CLIP)
            camera.getViewMatrix(viewScratch, 0)

            // ── Feature points ────────────────────────────────────────────
            frame.acquirePointCloud().use { pointCloud ->
                livePointCount = pointCloud.points.remaining() / 4
                if (livePointCount > 0) {
                    if (isAccumulating) accumulator.update(pointCloud)
                    pointCloudRenderer.update(pointCloud)
                    pointCloudRenderer.draw(viewScratch, projScratch)
                }
            }

            // ── Depth image: overlay + dense back-projection ──────────────
            if (depthSupported) {
                if (isDepthOverlayEnabled) depthRenderer.draw(frame)

                if (isAccumulating) {
                    val written = DepthProjector.project(
                        frame, depthScratch, 0, DEPTH_SCRATCH_POINTS, depthStride
                    )
                    if (written > 0) {
                        depthAccumulator.insertBatch(depthScratch, written, 0)
                        val newDepthSize = depthAccumulator.size
                        if (newDepthSize - lastDirtyDepthSize >= dirtyBatchSize) {
                            lastDirtyDepthSize = newDepthSize
                            // Merge depth + feature points into the GL-thread buffer
                            val featSize  = accumulator.size
                            val totalNeeded = (newDepthSize + featSize) * 4
                            if (mergedBuf.size < totalNeeded) mergedBuf = FloatArray(totalNeeded shl 1)
                            val actualDepth = depthAccumulator.exportMergedInto(mergedBuf, 0)
                            val actualFeat  = accumulator.snapshotInto(mergedBuf, actualDepth * 4)
                            accumulatedRenderer.markDirty(mergedBuf, actualDepth + actualFeat)
                        }
                    }
                }
            }

            // Draw accumulated cloud (dirty-flag: only re-uploads on batch flush)
            accumulatedRenderer.draw(viewScratch, projScratch)
        }

        val stateLabel   = when (trackingState) {
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

        val totalAccum = accumulator.size + depthAccumulator.size
        onStatusUpdate(stateLabel, failureLabel, livePointCount, totalAccum, fps)

        // ── Performance hint: frame work END ──────────────────────────────
        performanceOptimizer.frameEnd()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Force-flush the accumulated cloud to the GPU (call on scan stop). Merges depth + feature points. */
    fun flushAccumulatedCloud() {
        val depthCnt = depthAccumulator.size
        val featCnt  = accumulator.size
        val total    = depthCnt + featCnt
        if (total == 0) {
            // Cloud was cleared — tell renderer to stop drawing old data
            accumulatedRenderer.clearCloud()
            mergedBuf = FloatArray(0)    // release large allocation; grows again on next scan
            lastDirtyDepthSize = 0
            return
        }

        val merged = FloatArray(total * 4)
        // Write near+far depth points into merged[0..depthCnt*4)
        val (depthBuf, actualDepth) = depthAccumulator.exportMerged()
        System.arraycopy(depthBuf, 0, merged, 0, actualDepth * 4)
        // Append ARCore feature points after depth data
        val actualFeat  = accumulator.snapshotInto(merged, actualDepth * 4)
        val actualTotal = actualDepth + actualFeat
        if (actualTotal > 0) accumulatedRenderer.markDirty(merged, actualTotal)
        lastDirtyDepthSize = depthAccumulator.size
    }

    /** Updates the accumulated-cloud depth range so the GLSL gradient matches the actual scan size. */
    fun setAccumDepthRange(m: Float) {
        accumulatedRenderer.depthRange = m.coerceAtLeast(3f)
    }

    /** Updates accumulated cloud global opacity [0..1] — wired to the cloud SeekBar. */
    fun setAccumAlpha(v: Float) {
        accumulatedRenderer.alphaScale = v.coerceIn(0f, 1f)
    }

    /** Updates the depth overlay blend opacity (0 = invisible, 1 = fully opaque). */
    fun setDepthOpacity(v: Float) {
        depthRenderer.opacity = v.coerceIn(0f, 1f)
    }

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

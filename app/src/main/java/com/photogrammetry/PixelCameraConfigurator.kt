package com.photogrammetry

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import android.view.Window
import android.view.WindowManager
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Session
import java.util.EnumSet

/**
 * Configures an ARCore [Session] for the Pixel 9 Pro's 50 MP main sensor.
 *
 * Strategy (each tier falls through to the next if unsupported):
 *   1. 60 fps  + depth (ideal — full Tensor ISP + depth pipeline active)
 *   2. 60 fps  + no depth requirement
 *   3. 30 fps  + depth (devices without 60 fps depth path)
 *   4. ARCore default (leave whatever ARCore picked)
 *
 * The config with the **largest image area** in the winning tier is selected —
 * more pixels → more feature points → denser point cloud.
 *
 * Must be called BEFORE [Session.resume].
 */
object PixelCameraConfigurator {

    private const val TAG = "PixelCameraConfigurator"

    data class CameraIntrinsics(
        /** Focal length in mm (physical, from Camera2 LENS_INFO_AVAILABLE_FOCAL_LENGTHS). */
        val focalLengthMm: Float,
        /** Physical sensor width in mm. */
        val sensorWidthMm: Float,
        /** Physical sensor height in mm. */
        val sensorHeightMm: Float,
        /** Full pixel array width. */
        val pixelArrayWidth: Int,
        /** Full pixel array height. */
        val pixelArrayHeight: Int
    ) {
        /** Horizontal field-of-view in degrees at full sensor resolution. */
        val hFovDeg: Float
            get() = Math.toDegrees(2.0 * Math.atan(sensorWidthMm / (2.0 * focalLengthMm))).toFloat()

        /** Diagonal field-of-view in degrees. */
        val diagFovDeg: Float
            get() {
                val diagMm = Math.sqrt((sensorWidthMm * sensorWidthMm + sensorHeightMm * sensorHeightMm).toDouble())
                return Math.toDegrees(2.0 * Math.atan(diagMm / (2.0 * focalLengthMm))).toFloat()
            }

        override fun toString() =
            "f=${focalLengthMm}mm  ${sensorWidthMm}×${sensorHeightMm}mm  " +
            "${pixelArrayWidth}×${pixelArrayHeight}px  HFoV=${hFovDeg}°"
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Selects the optimal [CameraConfig] and applies it to [session].
     * @return the selected config, or null if ARCore's default was kept.
     */
    fun configure(session: Session): CameraConfig? {
        // Tier 1 — 60 fps + depth
        var config = selectBest(session,
            fps     = EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_60),
            depth   = EnumSet.of(CameraConfig.DepthSensorUsage.REQUIRE_AND_USE)
        )

        // Tier 2 — 60 fps, no depth requirement
        if (config == null) {
            config = selectBest(session,
                fps   = EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_60),
                depth = null
            )
        }

        // Tier 3 — 30 fps + depth
        if (config == null) {
            config = selectBest(session,
                fps   = null,
                depth = EnumSet.of(CameraConfig.DepthSensorUsage.REQUIRE_AND_USE)
            )
        }

        if (config != null) {
            session.cameraConfig = config
            Log.i(TAG, "Selected camera config: ${config.imageSize}  " +
                "fps=${config.fpsRange}  depth=${config.depthSensorUsage}")
            validateDepthAspect(config)
        } else {
            Log.w(TAG, "No preferred config found; keeping ARCore default")
        }

        return config
    }

    /**
     * Improvement #11 — intrinsics/depth aspect ratio validation.
     *
     * The Pixel 9 Pro depth image is 180×160 (aspect 9:8).  If the selected
     * colour camera config has a substantially different aspect ratio, the
     * [com.photogrammetry.rendering.DepthProjector] intrinsic scaling may
     * produce slight mis-registrations.  Log a warning so developers can
     * investigate or choose a different config tier.
     */
    private fun validateDepthAspect(config: CameraConfig) {
        val camAspect   = config.imageSize.width.toFloat() / config.imageSize.height
        val depthAspect = 180f / 160f   // Pixel 9 Pro DEPTH16 dimensions
        val diff = kotlin.math.abs(camAspect - depthAspect)
        if (diff > 0.1f) {
            Log.w(TAG, "Camera config aspect %.3f differs from depth aspect %.3f by %.3f — " +
                "back-projection intrinsic scaling may be inaccurate".format(
                    camAspect, depthAspect, diff))
        } else {
            Log.d(TAG, "Camera/depth aspect ratio match OK (Δ=%.3f)".format(diff))
        }
    }

    /**
     * Improvement #18 — high-framerate display hint.
     *
     * Requests the Pixel 9 Pro's LTPO OLED panel to run at its maximum supported
     * refresh rate (up to 120 Hz) rather than defaulting to 60 Hz.  Combined with
     * ARCore's 60 fps capture, this gives the compositor an extra rendering budget
     * and reduces touch latency during gestures.
     *
     * Call from [Activity.onCreate] after [setContentView].
     */
    fun enableHighFramerateDisplay(window: Window) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+: set preferred refresh rate to 0 = "no preference" = let the
            // display driver choose the highest supported rate dynamically (LTPO)
            val params = window.attributes
            params.preferredRefreshRate = 0f
            window.attributes = params
            Log.d(TAG, "Preferred refresh rate set to 0 (driver-maximum)")
        }
    }

    /**
     * Reads physical lens + sensor metadata for the back-facing camera via Camera2.
     * This data is fixed per-device (not per-session) and is useful for computing
     * metric depth from the point cloud.
     */
    fun readPhysicalIntrinsics(context: android.content.Context): CameraIntrinsics? {
        val mgr = context.getSystemService(CameraManager::class.java)
        for (id in mgr.cameraIdList) {
            val chars  = mgr.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
            if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

            val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: continue
            val sensorSize   = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)        ?: continue
            val pixelArray   = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)     ?: continue

            val intrinsics = CameraIntrinsics(
                focalLengthMm    = focalLengths[0],
                sensorWidthMm    = sensorSize.width,
                sensorHeightMm   = sensorSize.height,
                pixelArrayWidth  = pixelArray.width,
                pixelArrayHeight = pixelArray.height
            )
            Log.i(TAG, "Camera2 intrinsics: $intrinsics")
            return intrinsics
        }
        return null
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun selectBest(
        session: Session,
        fps:   EnumSet<CameraConfig.TargetFps>?,
        depth: EnumSet<CameraConfig.DepthSensorUsage>?
    ): CameraConfig? {
        val filter = CameraConfigFilter(session)
            .setFacingDirection(CameraConfig.FacingDirection.BACK)
        if (fps   != null) filter.setTargetFps(fps)
        if (depth != null) filter.setDepthSensorUsage(depth)

        val configs = session.getSupportedCameraConfigs(filter)
        // Choose the config with the largest image area (= more source pixels for features)
        return configs.maxByOrNull { it.imageSize.width.toLong() * it.imageSize.height }
    }
}

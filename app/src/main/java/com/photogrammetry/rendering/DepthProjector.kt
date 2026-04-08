package com.photogrammetry.rendering

import android.util.Log
import com.google.ar.core.Frame

/**
 * Back-projects the ARCore DEPTH16 image into dense world-space 3D points using
 * pinhole camera intrinsics supplied by ARCore itself.
 *
 * â”€â”€ Why depth back-projection on the Pixel 9 Pro? â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
 * ARCore feature tracking yields ~200â€“500 feature points per frame.
 * The Pixel 9 Pro's computational depth image is 180 Ã— 160 = 28 800 pixels.
 * Back-projecting only the confident depth pixels can add thousands of
 * additional world-space points per frame â€” all correctly aligned to the
 * colour camera pose.
 *
 * â”€â”€ Coordinate system â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
 * ARCore uses OpenGL conventions: Y-up, camera looks along -Z.
 *
 * Depth image pixel (u, v) at depth D metres â†’ camera space:
 *   X_cam =  (u - cx_d) * D / fx_d
 *   Y_cam = -(v - cy_d) * D / fy_d   (image Y is down, camera Y is up â†’ flip)
 *   Z_cam = -D                         (scene is in front = -Z in OpenGL)
 *
 * Camera intrinsics (fx, fy, cx, cy) are from [camera.imageIntrinsics] and
 * scaled to the depth image's resolution.
 *
 * Then multiply by the camera-to-world matrix (= inverse of ARCore's view matrix).
 *
 * â”€â”€ ARCore DEPTH16 pixel layout â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
 *   bits  0â€“12  depth in millimetres  (0 = unknown)
 *   bits 13â€“15  confidence 0â€“7        (0 = lowest, 7 = highest)
 *
 * â”€â”€ Output format â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
 * Writes world-space [x, y, z, confidence_0_1] quads into [outBuf] starting
 * at byte-offset [outOffset] (in floats).
 * Returns the number of points written.
 */
object DepthProjector {

    private const val TAG = "DepthProjector"

    // Depth validity thresholds
    private const val MIN_DEPTH_MM    = 150
    private const val MAX_DEPTH_MM    = 4_500
    private const val MIN_CONFIDENCE  = 3        // 0â€“7 scale
    private const val EDGE_THRESH_MM  = 200      // bilateral edge filter threshold    // Multiply instead of divide: avoids FP division in the per-pixel hot loop
    private const val INV_7            = 1f / 7f  // maps confidence 0-7 → 0.0-1.0
    // Pre-allocated scratch â€” no per-frame heap allocation
    private val viewMatrix   = FloatArray(16)
    // prevRowDepths: holds depth mm for the most-recently-visited stride row
    // sized to the widest known depth image (ARCore max is 240 px wide)
    private val prevRowDepths = ShortArray(512) { 0 }

    /**
     * Projects confident depth pixels into world-space, writing into [outBuf].
     *
     * Improvement #2 â€” rigid-body view-matrix inverse:
     *   Instead of [Matrix.invertM] (full 4Ã—4 Gaussian elimination), the view
     *   matrix for a rigid-body transform V = [R | t; 0 | 1] has the analytic
     *   inverse Vâ»Â¹ = [Ráµ€ | â€“Ráµ€Â·t; 0 | 1], computable with 9 read + 9 FMA ops
     *   rather than ~100 multiply/divide/subtract operations.
     *
     * Improvement #3 â€” configurable stride:
     *   [stride] = 1 â†’ every pixel (28 800/frame at 180Ã—160)
     *   [stride] = 2 â†’ every 2nd row & column (7 200/frame) â€” default
     *   [stride] = 4 â†’ every 4th (1 800/frame) â€” thermal throttle fallback
     *
     * Improvement #4 â€” depth-edge bilateral filter:
     *   Flying pixels appear at silhouette edges where the foreground depth
     *   transitions abruptly to background.  Before accepting a pixel, compare
     *   its depth to the same column in the previous stride-row.  If the
     *   difference exceeds [EDGE_THRESH_MM] the pixel is discarded.
     *
     * @param stride pixel sampling interval (default 2); must be >= 1.
     */
    fun project(
        frame:      Frame,
        outBuf:     FloatArray,
        outOffset:  Int,
        maxPoints:  Int,
        stride:     Int = 2
    ): Int {
        val camera     = frame.camera
        val depthImage = try {
            frame.acquireDepthImage16Bits()
        } catch (e: Exception) {
            return 0
        }

        var written = 0
        try {
            val dw = depthImage.width
            val dh = depthImage.height

            // â”€â”€ Camera intrinsics scaled to depth resolution â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            val intrinsics = camera.imageIntrinsics
            val texW = intrinsics.imageDimensions[0].toFloat()
            val texH = intrinsics.imageDimensions[1].toFloat()
            val scaleX = dw / texW
            val scaleY = dh / texH
            val fxD = intrinsics.focalLength[0]    * scaleX
            val fyD = intrinsics.focalLength[1]    * scaleY
            val cxD = intrinsics.principalPoint[0] * scaleX
            val cyD = intrinsics.principalPoint[1] * scaleY
            // Hoist reciprocals: 2 divides once → 2 muls per pixel (faster on Tensor G4)
            val invFxD = 1f / fxD
            val invFyD = 1f / fyD

            // â”€â”€ Fast rigid-body view-matrix inverse â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            // V = [R | t; 0 | 1]  â†’  Vâ»Â¹ = [Ráµ€ | -Ráµ€Â·t; 0 | 1]
            // Avoids Matrix.invertM() on the hot path (saves ~100 FP ops/frame)
            camera.getViewMatrix(viewMatrix, 0)
            val r00 = viewMatrix[0];  val r10 = viewMatrix[1];  val r20 = viewMatrix[2]
            val r01 = viewMatrix[4];  val r11 = viewMatrix[5];  val r21 = viewMatrix[6]
            val r02 = viewMatrix[8];  val r12 = viewMatrix[9];  val r22 = viewMatrix[10]
            val tx  = viewMatrix[12]; val ty  = viewMatrix[13]; val tz  = viewMatrix[14]
            // Camera-to-world upper 3Ã—3 is Ráµ€ (column-major â†’ swap row/col)
            val c00 = r00;  val c10 = r10;  val c20 = r20;  val c30 = -(r00*tx + r10*ty + r20*tz)
            val c01 = r01;  val c11 = r11;  val c21 = r21;  val c31 = -(r01*tx + r11*ty + r21*tz)
            val c02 = r02;  val c12 = r12;  val c22 = r22;  val c32 = -(r02*tx + r12*ty + r22*tz)

            // â”€â”€ Per-pixel back-projection with stride and edge filter â”€â”€â”€â”€â”€â”€
            val plane     = depthImage.planes[0]
            val buf       = plane.buffer
            val rowStride = plane.rowStride

            // Initialise prev-row buffer to 0 (= unknown depth) for first row
            prevRowDepths.fill(0, 0, dw)

            var v = 0
            while (v < dh && written < maxPoints) {
                var u = 0
                while (u < dw && written < maxPoints) {
                    // Seek to the exact strided pixel — buf.getShort() advances
                    // sequentially, so without repositioning u+=stride would read
                    // the wrong pixel when stride > 1.
                    buf.position(v * rowStride + u * 2)
                    val raw  = buf.getShort().toInt() and 0xFFFF
                    val dmm  = raw and 0x1FFF
                    val conf = (raw ushr 13) and 0x7

                    if (dmm in MIN_DEPTH_MM..MAX_DEPTH_MM && conf >= MIN_CONFIDENCE) {
                        // Depth-edge bilateral filter: discard flying pixels
                        val prevD = prevRowDepths[u].toInt() and 0xFFFF
                        val edgeOk = prevD == 0 || kotlin.math.abs(dmm - prevD) <= EDGE_THRESH_MM

                        if (edgeOk) {
                            val D  = dmm * 0.001f
                            val xc =  (u - cxD) * D * invFxD
                            val yc = -(v - cyD) * D * invFyD
                            val zc = -D

                            val base = outOffset + written * 4
                            outBuf[base]     = c00*xc + c10*yc + c20*zc + c30
                            outBuf[base + 1] = c01*xc + c11*yc + c21*zc + c31
                            outBuf[base + 2] = c02*xc + c12*yc + c22*zc + c32
                            outBuf[base + 3] = conf * INV_7
                            written++
                        }
                    }
                    // Update prev-row slot for this column
                    prevRowDepths[u] = (dmm and 0xFFFF).toShort()
                    u += stride
                }
                v += stride
            }
        } finally {
            depthImage.close()
        }

        return written
    }
}

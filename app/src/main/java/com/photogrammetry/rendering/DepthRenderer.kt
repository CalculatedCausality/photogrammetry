package com.photogrammetry.rendering

import android.content.Context
import android.opengl.GLES30
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renders the ARCore depth image as a semi-transparent jet-colormap overlay above
 * the camera background.
 *
 * Requires [com.google.ar.core.Config.DepthMode.AUTOMATIC] to be active in the
 * ARCore [com.google.ar.core.Session]. On devices without a depth sensor the
 * [draw] call silently returns without rendering anything.
 *
 * ARCore DEPTH16 pixel layout:
 *   bits  0–12 : depth in millimetres (0 = unknown / invalid)
 *   bits 13–15 : confidence (0 = unknown, 7 = highest)
 *
 * Shaders: assets/shaders/depth.vert / depth.frag
 */
class DepthRenderer {

    companion object {
        private const val TAG           = "DepthRenderer"
        private const val FLOAT_SIZE    = 4
        private const val COORDS_2D     = 2
        private const val QUAD_VERTICES = 4

        /** Depth distance (mm) mapped to the far end of the jet colormap. */
        private const val MAX_DEPTH_MM = 5000f

        private val NDC_QUAD = floatArrayOf(
            -1f, -1f,
             1f, -1f,
            -1f,  1f,
             1f,  1f
        )

        private val DEFAULT_UVS = floatArrayOf(
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f
        )
    }

    private var program        = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var textureHandle  = 0
    private var opacityHandle  = 0
    private var maxDepthHandle = 0

    private val texId = IntArray(1)
    private var texWidth  = -1
    private var texHeight = -1

    // VAO caches vertex-attrib state for the overlay quad.
    // [0] = position VBO (static NDC), [1] = UV VBO (updated on geometry change)
    private val vaoId  = IntArray(1)
    private val vboIds = IntArray(2)

    // Reusable compact staging buffer — allocated once and reused every frame.
    // Re-allocated only when depth image dimensions change (rare).
    private var compactBuf: ByteBuffer? = null
    private var compactBufBytes = 0

    private lateinit var quadPosBuffer: FloatBuffer
    private lateinit var uvBuffer:      FloatBuffer

    /** Blend opacity of the depth overlay (0 = invisible, 1 = fully opaque). */
    @Volatile var opacity: Float = 0.5f

    // -------------------------------------------------------------------------
    // Initialisation (GL thread)
    // -------------------------------------------------------------------------

    fun createOnGlThread(context: Context) {
        val vert = ShaderUtil.loadGLShader(TAG, context, GLES30.GL_VERTEX_SHADER,   "shaders/depth.vert")
        val frag = ShaderUtil.loadGLShader(TAG, context, GLES30.GL_FRAGMENT_SHADER, "shaders/depth.frag")
        program = ShaderUtil.createAndLinkProgram(TAG, vert, frag, "a_Position", "a_TexCoord")

        positionHandle = GLES30.glGetAttribLocation(program,  "a_Position")
        texCoordHandle = GLES30.glGetAttribLocation(program,  "a_TexCoord")
        textureHandle  = GLES30.glGetUniformLocation(program, "u_DepthTexture")
        opacityHandle  = GLES30.glGetUniformLocation(program, "u_Opacity")
        maxDepthHandle = GLES30.glGetUniformLocation(program, "u_MaxDepthMm")

        GLES30.glGenTextures(1, texId, 0)

        quadPosBuffer = directFloatBuffer(NDC_QUAD)
        uvBuffer      = directFloatBuffer(DEFAULT_UVS)

        // ── VBOs + VAO for the overlay quad ─────────────────────────────────
        GLES30.glGenBuffers(2, vboIds, 0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboIds[0])
        quadPosBuffer.rewind()
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER, NDC_QUAD.size * FLOAT_SIZE,
            quadPosBuffer, GLES30.GL_STATIC_DRAW
        )

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboIds[1])
        uvBuffer.rewind()
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER, DEFAULT_UVS.size * FLOAT_SIZE,
            uvBuffer, GLES30.GL_DYNAMIC_DRAW
        )

        GLES30.glGenVertexArrays(1, vaoId, 0)
        GLES30.glBindVertexArray(vaoId[0])

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboIds[0])
        GLES30.glVertexAttribPointer(positionHandle, COORDS_2D, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glEnableVertexAttribArray(positionHandle)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboIds[1])
        GLES30.glVertexAttribPointer(texCoordHandle, COORDS_2D, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glEnableVertexAttribArray(texCoordHandle)

        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        ShaderUtil.checkGLError(TAG, "createOnGlThread")
    }

    // -------------------------------------------------------------------------
    // Per-frame render (GL thread)
    // -------------------------------------------------------------------------

    fun draw(frame: Frame) {
        val depthImage = try {
            frame.acquireDepthImage16Bits()
        } catch (e: Exception) {
            // Depth not yet available or not supported on this device — skip silently.
            return
        }

        try {
            val w = depthImage.width
            val h = depthImage.height

            if (frame.hasDisplayGeometryChanged()) {
                quadPosBuffer.rewind()
                frame.transformCoordinates2d(
                    Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quadPosBuffer,
                    Coordinates2d.TEXTURE_NORMALIZED,                   uvBuffer
                )
                // Push updated UVs into the VBO so the VAO sees the new values
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboIds[1])
                uvBuffer.rewind()
                GLES30.glBufferSubData(
                    GLES30.GL_ARRAY_BUFFER, 0,
                    DEFAULT_UVS.size * FLOAT_SIZE, uvBuffer
                )
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
            }

            val plane     = depthImage.planes[0]
            val rawBuffer = plane.buffer

            uploadDepthTexture(rawBuffer, w, h, plane.rowStride)
            renderOverlay()

        } finally {
            // MUST close to release the underlying camera frame buffer.
            depthImage.close()
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Uploads 16-bit depth data to a GL_R16UI texture.
     *
     * If [rowStride] equals `width * 2` the upload is a single call; otherwise
     * each row is copied into a compact staging buffer first to strip padding.
     */
    private fun uploadDepthTexture(rawBuffer: ByteBuffer, w: Int, h: Int, rowStride: Int) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId[0])

        if (w != texWidth || h != texHeight) {
            // GL_RG8 is a normalized 2-channel 8-bit format — supports GL_LINEAR.
            // Channel layout: R = depth (0–255, mapped from 0–MAX_DEPTH_MM),
            //                 G = confidence (0–255, mapped from 0–7)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RG8,
                w, h, 0,
                GLES30.GL_RG, GLES30.GL_UNSIGNED_BYTE, null
            )
            texWidth  = w
            texHeight = h
        }

        // Convert DEPTH16 → RG8: pack normalized 8-bit depth into R, confidence into G.
        val pixelRowBytes = w * 2   // src: 2 bytes per uint16
        val dstRowBytes   = w * 2   // dst: 2 bytes per RG8 pixel
        val needed        = h * dstRowBytes
        if (compactBuf == null || compactBufBytes < needed) {
            compactBuf      = ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder())
            compactBufBytes = needed
        }
        val dst = compactBuf!!
        dst.clear()

        val maxDepthMm = MAX_DEPTH_MM.toInt()
        for (row in 0 until h) {
            rawBuffer.position(row * rowStride)
            for (col in 0 until w) {
                val raw  = rawBuffer.getShort().toInt() and 0xFFFF
                val dmm  = (raw and 0x1FFF).coerceAtMost(maxDepthMm)
                val conf = (raw ushr 13) and 0x7
                // Depth → 0..255 (20mm per step at 5 m range, sufficient for overlay)
                dst.put(((dmm * 255) / maxDepthMm).toByte())
                // Confidence → 0..255  (7 * 36 = 252)
                dst.put((conf * 36).toByte())
            }
        }
        dst.rewind()
        GLES30.glTexSubImage2D(
            GLES30.GL_TEXTURE_2D, 0, 0, 0, w, h,
            GLES30.GL_RG, GLES30.GL_UNSIGNED_BYTE, dst
        )


        ShaderUtil.checkGLError(TAG, "uploadDepthTexture")
    }

    private fun renderOverlay() {
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)

        GLES30.glUseProgram(program)

        // Texture is already bound by uploadDepthTexture() — no redundant bind needed.
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glUniform1i(textureHandle, 0)
        GLES30.glUniform1f(opacityHandle, opacity)
        GLES30.glUniform1f(maxDepthHandle, MAX_DEPTH_MM)

        // VAO has all vertex-attrib state cached — no per-frame glVertexAttribPointer
        GLES30.glBindVertexArray(vaoId[0])
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, QUAD_VERTICES)
        GLES30.glBindVertexArray(0)

        GLES30.glUseProgram(0)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)

        ShaderUtil.checkGLError(TAG, "renderOverlay")
    }

    private fun directFloatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * FLOAT_SIZE)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .also { it.put(data); it.rewind() }
}

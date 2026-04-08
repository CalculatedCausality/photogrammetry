package com.photogrammetry.rendering

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES30
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renders the live ARCore camera feed as a full-screen background quad using an
 * OES (External) texture, which avoids a GPU-side copy of the YUV camera buffer.
 *
 * Shaders: assets/shaders/background.vert / background.frag
 */
class BackgroundRenderer {

    companion object {
        private const val TAG           = "BackgroundRenderer"
        private const val FLOAT_SIZE    = 4
        private const val COORDS_2D     = 2
        private const val QUAD_VERTICES = 4  // triangle strip

        // NDC positions of the full-screen quad (triangle strip order)
        private val NDC_QUAD_COORDS = floatArrayOf(
            -1f, -1f,   // bottom-left
             1f, -1f,   // bottom-right
            -1f,  1f,   // top-left
             1f,  1f    // top-right
        )

        // Default UV values (will be replaced by ARCore's transform on first frame)
        private val DEFAULT_UV_COORDS = floatArrayOf(
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f
        )
    }

    /** OES texture ID that ARCore writes the camera image into. */
    var textureId: Int = -1
        private set

    private var program             = 0
    private var positionHandle      = 0
    private var texCoordHandle      = 0
    private var textureUniformHandle = 0

    // VAO caches vertex-attrib state so draw() doesn't repeat glVertexAttribPointer each frame.
    private val vaoId  = IntArray(1)
    // [0] = position VBO (static NDC quad), [1] = UV VBO (updated on geometry change)
    private val vboIds = IntArray(2)

    // NIO buffers still needed for ARCore's transformCoordinates2d API (CPU-side).
    private lateinit var quadPositionBuffer: FloatBuffer
    private lateinit var transformedUvBuffer: FloatBuffer

    // -------------------------------------------------------------------------
    // Initialisation (must be called on the GL thread)
    // -------------------------------------------------------------------------

    fun createOnGlThread(context: Context) {
        // Create the external OES texture for the camera feed
        val texIds = IntArray(1)
        GLES30.glGenTextures(1, texIds, 0)
        textureId = texIds[0]

        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)

        val vertShader = ShaderUtil.loadGLShader(TAG, context, GLES30.GL_VERTEX_SHADER,   "shaders/background.vert")
        val fragShader = ShaderUtil.loadGLShader(TAG, context, GLES30.GL_FRAGMENT_SHADER, "shaders/background.frag")
        program = ShaderUtil.createAndLinkProgram(TAG, vertShader, fragShader, "a_Position", "a_TexCoord")

        positionHandle       = GLES30.glGetAttribLocation(program,  "a_Position")
        texCoordHandle       = GLES30.glGetAttribLocation(program,  "a_TexCoord")
        textureUniformHandle = GLES30.glGetUniformLocation(program, "u_Texture")

        quadPositionBuffer  = directFloatBuffer(NDC_QUAD_COORDS)
        transformedUvBuffer = directFloatBuffer(DEFAULT_UV_COORDS)

        // ── VBOs + VAO ──────────────────────────────────────────────────────
        GLES30.glGenBuffers(2, vboIds, 0)

        // Position VBO: static full-screen quad, never changes
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboIds[0])
        quadPositionBuffer.rewind()
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            NDC_QUAD_COORDS.size * FLOAT_SIZE,
            quadPositionBuffer,
            GLES30.GL_STATIC_DRAW
        )

        // UV VBO: updated whenever display geometry changes (orientation, size)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboIds[1])
        transformedUvBuffer.rewind()
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            DEFAULT_UV_COORDS.size * FLOAT_SIZE,
            transformedUvBuffer,
            GLES30.GL_DYNAMIC_DRAW
        )

        // VAO records attrib pointers once; draw() just binds the VAO
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
    // Per-frame render (must be called on the GL thread)
    // -------------------------------------------------------------------------

    fun draw(frame: Frame) {
        // ARCore tells us when display geometry changed (orientation, size).
        if (frame.hasDisplayGeometryChanged()) {
            quadPositionBuffer.rewind()
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quadPositionBuffer,
                Coordinates2d.TEXTURE_NORMALIZED,                   transformedUvBuffer
            )
            // Push the updated UV coords into the VBO so the VAO uses them
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboIds[1])
            transformedUvBuffer.rewind()
            GLES30.glBufferSubData(
                GLES30.GL_ARRAY_BUFFER,
                0,
                DEFAULT_UV_COORDS.size * FLOAT_SIZE,
                transformedUvBuffer
            )
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        }

        // Skip the very first incomplete frame
        if (frame.timestamp == 0L) return

        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)

        GLES30.glUseProgram(program)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glUniform1i(textureUniformHandle, 0)

        // VAO has all vertex-attrib state cached — no glVertexAttribPointer needed
        GLES30.glBindVertexArray(vaoId[0])
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, QUAD_VERTICES)
        GLES30.glBindVertexArray(0)

        GLES30.glUseProgram(0)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)

        ShaderUtil.checkGLError(TAG, "draw")
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun directFloatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * FLOAT_SIZE)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .also { it.put(data); it.rewind() }
}

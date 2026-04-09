package com.photogrammetry.rendering

import android.content.Context
import android.opengl.GLES30
import android.opengl.Matrix
import com.google.ar.core.PointCloud

/**
 * Renders ARCore's real-time 3D feature-point cloud using GL_POINTS.
 *
 * ARCore's PointCloud buffer layout per point:
 *   [ X (f32) | Y (f32) | Z (f32) | Confidence (f32) ]
 *   stride = 16 bytes
 *
 * Points are coloured on a red–green gradient by confidence:
 *   low confidence → red, high confidence → green.
 *
 * GPU upload strategy for large datasets
 * ───────────────────────────────────────
 * • glBufferData(null) orphans the old VBO (driver may keep feeding old data
 *   while we write into a new store — "buffer orphaning") but is called only
 *   when the count actually grows beyond the current GPU capacity.
 * • glBufferSubData is used for same-or-smaller uploads; it avoids the driver
 *   allocation round-trip entirely.
 * • VBO capacity grows by doubling so reallocs are O(log n) over the session.
 *
 * Shaders: assets/shaders/pointcloud.vert / pointcloud.frag
 */
class PointCloudRenderer {

    companion object {
        private const val TAG               = "PointCloudRenderer"
        private const val FLOAT_SIZE        = 4
        private const val FLOATS_PER_POINT  = 4              // X, Y, Z, Confidence
        private const val BYTES_PER_POINT   = FLOATS_PER_POINT * FLOAT_SIZE  // 16
        private const val POSITION_SIZE     = 3              // vec3
        private const val CONFIDENCE_SIZE   = 1
        private const val CONFIDENCE_OFFSET = 3 * FLOAT_SIZE // 12 bytes
        private const val INITIAL_CAPACITY  = 1_024          // number of points
        private const val POINT_SIZE_DP     = 8f              // density-independent size
    }

    private var program           = 0
    private var positionHandle    = 0
    private var confidenceHandle  = 0
    private var mvpHandle         = 0
    private var pointSizeHandle   = 0

    private var pointSizePx = POINT_SIZE_DP   // overwritten in createOnGlThread

    private val vboId = IntArray(1)
    private val vaoId = IntArray(1)   // VAO — caches vertex attrib state
    private var numPoints    = 0
    private var vboCapacity  = 0

    private val mvpScratch = FloatArray(16)

    // -------------------------------------------------------------------------
    // Initialisation (GL thread)
    // -------------------------------------------------------------------------

    fun createOnGlThread(context: Context) {
        pointSizePx = POINT_SIZE_DP * context.resources.displayMetrics.density.coerceAtLeast(1f)
        val vert = ShaderUtil.loadGLShader(TAG, context, GLES30.GL_VERTEX_SHADER,   "shaders/pointcloud.vert")
        val frag = ShaderUtil.loadGLShader(TAG, context, GLES30.GL_FRAGMENT_SHADER, "shaders/pointcloud.frag")
        program = ShaderUtil.createAndLinkProgram(TAG, vert, frag, "a_Position", "a_Confidence")

        positionHandle   = GLES30.glGetAttribLocation(program,  "a_Position")
        confidenceHandle = GLES30.glGetAttribLocation(program,  "a_Confidence")
        mvpHandle        = GLES30.glGetUniformLocation(program, "u_ModelViewProjection")
        pointSizeHandle  = GLES30.glGetUniformLocation(program, "u_PointSize")

        // Allocate VBO first, then record state in VAO
        GLES30.glGenBuffers(1, vboId, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId[0])
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            INITIAL_CAPACITY * BYTES_PER_POINT,
            null,
            GLES30.GL_DYNAMIC_DRAW
        )
        vboCapacity = INITIAL_CAPACITY

        // VAO records vertex attrib setup once; draw() just binds the VAO
        GLES30.glGenVertexArrays(1, vaoId, 0)
        GLES30.glBindVertexArray(vaoId[0])

        GLES30.glVertexAttribPointer(positionHandle,   POSITION_SIZE,   GLES30.GL_FLOAT, false, BYTES_PER_POINT, 0)
        GLES30.glEnableVertexAttribArray(positionHandle)
        GLES30.glVertexAttribPointer(confidenceHandle, CONFIDENCE_SIZE, GLES30.GL_FLOAT, false, BYTES_PER_POINT, CONFIDENCE_OFFSET)
        GLES30.glEnableVertexAttribArray(confidenceHandle)

        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        ShaderUtil.checkGLError(TAG, "createOnGlThread")
    }

    // -------------------------------------------------------------------------
    // Per-frame update (GL thread)
    // -------------------------------------------------------------------------

    private var lastPointCloudTimestamp = -1L

    fun update(pointCloud: PointCloud) {
        // Skip redundant VBO upload if this is the same cloud from the prior frame
        if (pointCloud.timestamp == lastPointCloudTimestamp) {
            numPoints = pointCloud.points.remaining() / 4
            return
        }
        lastPointCloudTimestamp = pointCloud.timestamp
        numPoints = pointCloud.points.remaining() / 4
        if (numPoints == 0) return

        val points = pointCloud.points
        points.rewind()

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId[0])
        if (numPoints > vboCapacity) growVbo(numPoints)

        GLES30.glBufferSubData(
            GLES30.GL_ARRAY_BUFFER, 0, numPoints * BYTES_PER_POINT, points
        )
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        ShaderUtil.checkGLError(TAG, "update")
    }

    // -------------------------------------------------------------------------
    // Per-frame draw (GL thread)
    // -------------------------------------------------------------------------

    fun draw(viewMatrix: FloatArray, projectionMatrix: FloatArray) {
        if (numPoints == 0) return

        Matrix.multiplyMM(mvpScratch, 0, projectionMatrix, 0, viewMatrix, 0)

        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(mvpHandle, 1, false, mvpScratch, 0)
        GLES30.glUniform1f(pointSizeHandle, pointSizePx)

        // VAO already has position + confidence attrib pointers set up
        GLES30.glBindVertexArray(vaoId[0])
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, numPoints)
        GLES30.glBindVertexArray(0)

        GLES30.glUseProgram(0)
        ShaderUtil.checkGLError(TAG, "draw")
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun growVbo(minPoints: Int) {
        var cap = if (vboCapacity == 0) INITIAL_CAPACITY else vboCapacity
        while (cap < minPoints) cap = cap shl 1

        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER, cap * BYTES_PER_POINT, null, GLES30.GL_DYNAMIC_DRAW
        )
        vboCapacity = cap
        // The VAO still references vboId[0] which retains the same attrib layout
    }
}


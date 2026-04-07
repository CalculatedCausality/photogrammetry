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
 * Shaders: assets/shaders/pointcloud.vert / pointcloud.frag
 */
class PointCloudRenderer {

    companion object {
        private const val TAG              = "PointCloudRenderer"
        private const val FLOAT_SIZE       = 4              // bytes per float
        private const val FLOATS_PER_POINT = 4              // X, Y, Z, Confidence
        private const val BYTES_PER_POINT  = FLOATS_PER_POINT * FLOAT_SIZE  // 16
        private const val POSITION_SIZE    = 3              // vec3
        private const val CONFIDENCE_SIZE  = 1
        private const val CONFIDENCE_OFFSET = 3 * FLOAT_SIZE // 12 bytes
        private const val INITIAL_VBO_POINTS = 1000
        private const val POINT_SIZE_PX    = 8f
    }

    private var program           = 0
    private var positionHandle    = 0
    private var confidenceHandle  = 0
    private var mvpHandle         = 0
    private var pointSizeHandle   = 0

    private val vboId = IntArray(1)
    private var numPoints = 0

    // -------------------------------------------------------------------------
    // Initialisation (GL thread)
    // -------------------------------------------------------------------------

    fun createOnGlThread(context: Context) {
        val vert = ShaderUtil.loadGLShader(TAG, context, GLES30.GL_VERTEX_SHADER,   "shaders/pointcloud.vert")
        val frag = ShaderUtil.loadGLShader(TAG, context, GLES30.GL_FRAGMENT_SHADER, "shaders/pointcloud.frag")
        program = ShaderUtil.createAndLinkProgram(TAG, vert, frag, "a_Position", "a_Confidence")

        positionHandle   = GLES30.glGetAttribLocation(program,  "a_Position")
        confidenceHandle = GLES30.glGetAttribLocation(program,  "a_Confidence")
        mvpHandle        = GLES30.glGetUniformLocation(program, "u_ModelViewProjection")
        pointSizeHandle  = GLES30.glGetUniformLocation(program, "u_PointSize")

        // Pre-allocate a GPU buffer; it will be grown dynamically as needed
        GLES30.glGenBuffers(1, vboId, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId[0])
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            INITIAL_VBO_POINTS * BYTES_PER_POINT,
            null,
            GLES30.GL_DYNAMIC_DRAW
        )
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        ShaderUtil.checkGLError(TAG, "createOnGlThread")
    }

    // -------------------------------------------------------------------------
    // Per-frame update — upload the new point cloud to the GPU (GL thread)
    // -------------------------------------------------------------------------

    fun update(pointCloud: PointCloud) {
        numPoints = pointCloud.numPoints
        if (numPoints == 0) return

        val points = pointCloud.points  // FloatBuffer: [x, y, z, conf, ...]
        points.rewind()

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId[0])
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            numPoints * BYTES_PER_POINT,
            points,
            GLES30.GL_DYNAMIC_DRAW
        )
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        ShaderUtil.checkGLError(TAG, "update")
    }

    // -------------------------------------------------------------------------
    // Per-frame draw (GL thread)
    // -------------------------------------------------------------------------

    fun draw(viewMatrix: FloatArray, projectionMatrix: FloatArray) {
        if (numPoints == 0) return

        val mvpMatrix = FloatArray(16)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
        GLES30.glUniform1f(pointSizeHandle, POINT_SIZE_PX)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId[0])

        // Position attribute: X Y Z at byte offset 0, stride 16
        GLES30.glVertexAttribPointer(
            positionHandle, POSITION_SIZE, GLES30.GL_FLOAT, false,
            BYTES_PER_POINT, 0
        )
        GLES30.glEnableVertexAttribArray(positionHandle)

        // Confidence attribute: 1 float at byte offset 12, stride 16
        GLES30.glVertexAttribPointer(
            confidenceHandle, CONFIDENCE_SIZE, GLES30.GL_FLOAT, false,
            BYTES_PER_POINT, CONFIDENCE_OFFSET
        )
        GLES30.glEnableVertexAttribArray(confidenceHandle)

        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, numPoints)

        GLES30.glDisableVertexAttribArray(positionHandle)
        GLES30.glDisableVertexAttribArray(confidenceHandle)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        ShaderUtil.checkGLError(TAG, "draw")
    }
}

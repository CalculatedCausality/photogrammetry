package com.photogrammetry.rendering

import android.content.Context
import android.opengl.GLES30
import android.opengl.Matrix

/**
 * Renders the **accumulated** world-space point cloud (both feature and depth
 * back-projected points) as a dense GL_POINTS cloud with a depth-based cool
 * blue-to-white colour gradient.
 *
 * Unlike [PointCloudRenderer], which re-draws the live ARCore per-frame feature
 * points every draw call, this renderer:
 *  • Holds a single GPU VBO for the full accumulated cloud.
 *  • Uses a dirty flag — the VBO is only re-uploaded when [markDirty] is called
 *    (e.g. when the user stops scanning or when the accumulator crosses a batch
 *    size threshold).  Between dirty marks only the MVP matrix is updated each
 *    frame, making render cost O(1) instead of O(n) for large clouds.
 *  • Uses a VAO to eliminate redundant GL vertex-attribute setup calls.
 *
 * Colour scheme:
 *   Near points (low |z|) → bright white
 *   Far points  (high |z|) → deep blue
 *
 * Shaders: assets/shaders/accumulated.vert / accumulated.frag
 */
class AccumulatedRenderer {

    companion object {
        private const val TAG             = "AccumulatedRenderer"
        private const val FLOATS_PER_POINT = 4    // x, y, z, confidence
        private const val BYTES_PER_FLOAT  = 4
        private const val INIT_CAPACITY    = 10_000  // initial VBO capacity in points
    }

    private var program        = 0
    private var positionHandle = 0
    private var mvpHandle      = 0
    private var pointSizeHandle = 0
    private var depthRangeHandle = 0   // u_DepthRange: max eye-depth in metres
    private var alphaHandle     = 0    // u_Alpha: global cloud opacity

    @Volatile var depthRange: Float = 8.0f   // default 0–8 m; updated by MainRenderer
    @Volatile var alphaScale: Float = 1.0f   // 1.0 = fully opaque, 0.0 = invisible

    private val vaoId  = IntArray(1)
    private val vboId  = IntArray(1)
    private var vboCapacity = 0       // in floats
    private var drawCount   = 0       // number of points currently on the GPU

    @Volatile private var dirty    = false
    @Volatile private var pendingBuf: FloatArray? = null
    @Volatile private var pendingCount: Int = 0

    // Cached staging buffer: allocated once and grown only when point count exceeds
    // the previous maximum — avoids ByteBuffer.allocateDirect on every dirty flush.
    private var uploadBuffer: java.nio.FloatBuffer? = null

    private val mvpScratch = FloatArray(16)

    // -------------------------------------------------------------------------
    // Initialisation (GL thread)
    // -------------------------------------------------------------------------

    fun createOnGlThread(context: Context) {
        val vert = ShaderUtil.loadGLShader(TAG, context, GLES30.GL_VERTEX_SHADER,   "shaders/accumulated.vert")
        val frag = ShaderUtil.loadGLShader(TAG, context, GLES30.GL_FRAGMENT_SHADER, "shaders/accumulated.frag")
        program = ShaderUtil.createAndLinkProgram(TAG, vert, frag, "a_Position")

        positionHandle  = GLES30.glGetAttribLocation(program,  "a_Position")
        mvpHandle       = GLES30.glGetUniformLocation(program, "u_ModelViewProjection")
        pointSizeHandle = GLES30.glGetUniformLocation(program, "u_PointSize")
        depthRangeHandle = GLES30.glGetUniformLocation(program, "u_DepthRange")
        alphaHandle     = GLES30.glGetUniformLocation(program, "u_Alpha")

        // VAO — caches vertex attribute state so draw() doesn't repeat setup
        GLES30.glGenVertexArrays(1, vaoId, 0)
        GLES30.glBindVertexArray(vaoId[0])

        GLES30.glGenBuffers(1, vboId, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId[0])

        // Reserve initial VBO storage
        val initBytes = INIT_CAPACITY * FLOATS_PER_POINT * BYTES_PER_FLOAT
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, initBytes, null, GLES30.GL_DYNAMIC_DRAW)
        vboCapacity = INIT_CAPACITY * FLOATS_PER_POINT

        // Set up vertex attrib pointer inside the VAO binding
        GLES30.glEnableVertexAttribArray(positionHandle)
        GLES30.glVertexAttribPointer(
            positionHandle, FLOATS_PER_POINT, GLES30.GL_FLOAT,
            false, FLOATS_PER_POINT * BYTES_PER_FLOAT, 0
        )

        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        ShaderUtil.checkGLError(TAG, "createOnGlThread")
    }

    // -------------------------------------------------------------------------
    // API (any thread)
    // -------------------------------------------------------------------------

    /**
     * Schedules [buf] (flat [x,y,z,conf]*[count]) as the next VBO upload.
     * Actual upload happens on the GL thread during [draw].
     *
     * Call this when the accumulated cloud changes (scan stop, batch flush, etc.).
     */
    fun markDirty(buf: FloatArray, count: Int) {
        pendingBuf   = buf
        pendingCount = count
        dirty        = true
    }

    /**
     * Immediately clears the rendered cloud without a VBO upload.
     * Must be called from the GL thread (e.g. via [GLSurfaceView.queueEvent]).
     * Use this when both accumulators are cleared so the old cloud stops rendering.
     */
    fun clearCloud() {
        pendingBuf   = null
        pendingCount = 0
        dirty        = false
        drawCount    = 0  // GL-thread-only field — safe here
    }

    // -------------------------------------------------------------------------
    // Per-frame render (GL thread)
    // -------------------------------------------------------------------------

    fun draw(viewMatrix: FloatArray, projMatrix: FloatArray) {
        if (dirty) flushUpload()
        if (drawCount == 0) return

        GLES30.glUseProgram(program)

        Matrix.multiplyMM(mvpScratch, 0, projMatrix, 0, viewMatrix, 0)
        GLES30.glUniformMatrix4fv(mvpHandle, 1, false, mvpScratch, 0)
        GLES30.glUniform1f(pointSizeHandle, 3.0f)
        GLES30.glUniform1f(depthRangeHandle, depthRange)
        GLES30.glUniform1f(alphaHandle, alphaScale)

        GLES30.glBindVertexArray(vaoId[0])
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, drawCount)
        GLES30.glBindVertexArray(0)

        GLES30.glUseProgram(0)
        ShaderUtil.checkGLError(TAG, "draw")
    }

    // -------------------------------------------------------------------------
    // Private
    // -------------------------------------------------------------------------

    private fun flushUpload() {
        val buf   = pendingBuf   ?: return
        val count = pendingCount
        dirty     = false

        val needed = count * FLOATS_PER_POINT
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId[0])

        if (needed > vboCapacity) {
            // Grow VBO with orphan + re-allocate
            var newCap = vboCapacity.coerceAtLeast(INIT_CAPACITY * FLOATS_PER_POINT)
            while (newCap < needed) newCap = newCap shl 1
            GLES30.glBufferData(
                GLES30.GL_ARRAY_BUFFER,
                newCap * BYTES_PER_FLOAT,
                null,
                GLES30.GL_DYNAMIC_DRAW
            )
            vboCapacity = newCap
        }

        // Reuse or grow the staging FloatBuffer — avoids allocateDirect per flush
        val cur = uploadBuffer
        val fbuf = if (cur != null && cur.capacity() >= needed) cur
                   else java.nio.ByteBuffer.allocateDirect(needed * BYTES_PER_FLOAT)
                       .order(java.nio.ByteOrder.nativeOrder())
                       .asFloatBuffer()
                       .also { uploadBuffer = it }
        fbuf.clear()
        fbuf.put(buf, 0, needed)
        fbuf.rewind()

        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, needed * BYTES_PER_FLOAT, fbuf)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        drawCount   = count
        pendingBuf  = null
    }
}

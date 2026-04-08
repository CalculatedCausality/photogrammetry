package com.photogrammetry.rendering

import android.content.Context
import android.opengl.GLES30
import android.util.Log

/**
 * Utility functions for loading, compiling, and linking OpenGL ES 3.0 shaders.
 * Shader source files are read from the app's assets directory.
 */
object ShaderUtil {

    /**
     * Loads a shader from the assets folder, compiles it, and returns the shader handle.
     *
     * @param tag     LogCat tag for error messages
     * @param context Used to open the asset
     * @param type    GLES30.GL_VERTEX_SHADER or GLES30.GL_FRAGMENT_SHADER
     * @param assetPath  Path relative to assets/, e.g. "shaders/pointcloud.vert"
     */
    fun loadGLShader(tag: String, context: Context, type: Int, assetPath: String): Int {
        val source = context.assets.open(assetPath).bufferedReader().use { it.readText() }

        val shader = GLES30.glCreateShader(type)
        check(shader != 0) { "glCreateShader failed for $assetPath" }

        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)

        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == GLES30.GL_FALSE) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            // Annotate source with line numbers for easy mapping to the compiler error
            val annotated = source.lines()
                .mapIndexed { i, line -> "%4d: %s".format(i + 1, line) }
                .joinToString("\n")
            throw RuntimeException("Compile error in $assetPath:\n$log\n--- Source ---\n$annotated")
        }

        return shader
    }

    /**
     * Links a vertex and fragment shader into a program.
     *
     * Attribute locations are bound in the order they are listed in [attributes],
     * starting at location 0. This must match the order in the vertex shader.
     */
    fun createAndLinkProgram(
        tag: String,
        vertShader: Int,
        fragShader: Int,
        vararg attributes: String
    ): Int {
        val program = GLES30.glCreateProgram()
        check(program != 0) { "glCreateProgram failed" }

        GLES30.glAttachShader(program, vertShader)
        GLES30.glAttachShader(program, fragShader)

        attributes.forEachIndexed { index, name ->
            GLES30.glBindAttribLocation(program, index, name)
        }

        GLES30.glLinkProgram(program)

        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == GLES30.GL_FALSE) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            throw RuntimeException("Link error: $log")
        }

        // Shaders can be detached after linking
        GLES30.glDetachShader(program, vertShader)
        GLES30.glDetachShader(program, fragShader)
        GLES30.glDeleteShader(vertShader)
        GLES30.glDeleteShader(fragShader)

        return program
    }

    /**
     * Drains the GL error queue. Throws if any error is found.
     * Call after significant GL operations during development to surface bugs early.
     */
    fun checkGLError(tag: String, label: String) {
        var error = GLES30.glGetError()
        while (error != GLES30.GL_NO_ERROR) {
            val msg = "$label: glError 0x${error.toString(16)}"
            Log.e(tag, msg)
            error = GLES30.glGetError()
            if (error == GLES30.GL_NO_ERROR) throw RuntimeException(msg)
        }
    }
}

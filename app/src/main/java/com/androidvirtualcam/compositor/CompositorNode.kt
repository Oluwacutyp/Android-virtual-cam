package com.androidvirtualcam.compositor

import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log

enum class ParamType {
    FLOAT,
    INT,
    BOOL,
    COLOR,
    STRING,
    VEC3,
    VEC4
}

data class Param(
    val type: ParamType,
    val value: Any
)

interface CompositorNode {
    val id: String
    val type: String
    val inputSockets: List<InputSocket>
    val outputSocket: OutputSocket
    val parameters: NodeParameters

    fun initialize()
    fun process(inputs: Map<String, GLTexture>): GLTexture
    fun release()

    fun getInputSocket(socketId: String): InputSocket? = inputSockets.find { it.id == socketId }
}

abstract class BaseCompositorNode(
    override val id: String,
    override val type: String,
    override val inputSockets: List<InputSocket>,
    override val outputSocket: OutputSocket,
    override var parameters: NodeParameters
) : CompositorNode {

    protected var isInitialized = false
    protected var programId: Int = 0

    protected val quadVertices = floatArrayOf(
        -1f, -1f, 0f, 0f,
         1f, -1f, 1f, 0f,
        -1f,  1f, 0f, 1f,
         1f,  1f, 1f, 1f
    )

    protected var vboId: Int = 0
    protected var vaoId: Int = 0

    override fun initialize() {
        if (isInitialized) return
        try {
            val vboIds = IntArray(1)
            GLES20.glGenBuffers(1, vboIds, 0)
            vboId = vboIds[0]

            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)
            val vertexBuffer = java.nio.ByteBuffer.allocateDirect(quadVertices.size * 4)
                .order(java.nio.ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(quadVertices)
            vertexBuffer.position(0)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, quadVertices.size * 4, vertexBuffer, GLES20.GL_STATIC_DRAW)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

            try {
                val vaoIds = IntArray(1)
                GLES30.glGenVertexArrays(1, vaoIds, 0)
                vaoId = vaoIds[0]
            } catch (e: Exception) {
                Log.w("BaseCompositorNode", "VAO not available, using ES2 path: ${e.message}")
                vaoId = 0
            }

            try {
                onInitialize()
            } catch (e: Exception) {
                Log.e("BaseCompositorNode", "onInitialize failed for $id, trying passthrough fallback", e)
                // Fallback to passthrough shader to prevent crash
                try {
                    programId = compileProgramInternal(PASSTHROUGH_VERT, PASSTHROUGH_FRAG)
                    Log.i("BaseCompositorNode", "Fallback passthrough program compiled for $id")
                } catch (re: Exception) {
                    Log.e("BaseCompositorNode", "Fallback also failed for $id", re)
                    throw e
                }
            }
            isInitialized = true
            Log.d("BaseCompositorNode", "Initialized $id type=$type program=$programId vbo=$vboId vao=$vaoId")
        } catch (e: Exception) {
            Log.e("BaseCompositorNode", "initialize failed for $id", e)
            // Don't crash app – allow to continue with invalid state, will be handled in process
            isInitialized = false
            // Try to at least have a program
            try {
                programId = compileProgramInternal(PASSTHROUGH_VERT, PASSTHROUGH_FRAG)
                isInitialized = true
            } catch (_: Exception) {}
        }
    }

    protected open fun onInitialize() {}

    // Internal compile without fallback – used for fallback itself
    private fun compileProgramInternal(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compileShaderInternal(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = compileShaderInternal(GLES20.GL_FRAGMENT_SHADER, fragmentSource)

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
            throw RuntimeException("Program link failed: $log")
        }

        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)

        return program
    }

    protected fun compileProgram(vertexSource: String, fragmentSource: String): Int {
        return try {
            val program = compileProgramInternal(vertexSource, fragmentSource)
            programId = program
            Log.d("BaseCompositorNode", "Program compiled $id type=$type program=$program")
            program
        } catch (e: Exception) {
            Log.e("BaseCompositorNode", "compileProgram failed for $id: ${e.message}, trying passthrough fallback", e)
            try {
                // Try passthrough vertex + original fragment? First try both passthrough
                val fallbackProgram = if (fragmentSource.contains("samplerExternalOES")) {
                    compileProgramInternal(PASSTHROUGH_VERT, PASSTHROUGH_FRAG_OES)
                } else {
                    compileProgramInternal(PASSTHROUGH_VERT, PASSTHROUGH_FRAG)
                }
                programId = fallbackProgram
                Log.i("BaseCompositorNode", "Fallback passthrough compiled for $id program=$fallbackProgram")
                fallbackProgram
            } catch (re: Exception) {
                Log.e("BaseCompositorNode", "Fallback passthrough also failed for $id", re)
                // Last resort: try simplest possible
                try {
                    val lastResort = compileProgramInternal(PASSTHROUGH_VERT, PASSTHROUGH_FRAG)
                    programId = lastResort
                    lastResort
                } catch (re2: Exception) {
                    Log.e("BaseCompositorNode", "Last resort failed for $id", re2)
                    throw e
                }
            }
        }
    }

    private fun compileShaderInternal(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) {
            throw RuntimeException("glCreateShader failed type=$type")
        }
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            val error = GLES20.glGetError()
            GLES20.glDeleteShader(shader)
            Log.e("BaseCompositorNode", "Shader compile failed type=$type log=$log glError=$error source=${source.take(800)}")
            throw RuntimeException("Shader compile failed ($type): $log (glError=$error)")
        }
        return shader
    }

    private fun compileShader(type: Int, source: String): Int {
        return try {
            compileShaderInternal(type, source)
        } catch (e: Exception) {
            Log.e("BaseCompositorNode", "compileShader exception type=$type, trying passthrough", e)
            // Fallback to passthrough shader source
            val fallbackSource = if (type == GLES20.GL_VERTEX_SHADER) PASSTHROUGH_VERT else {
                if (source.contains("samplerExternalOES")) PASSTHROUGH_FRAG_OES else PASSTHROUGH_FRAG
            }
            try {
                compileShaderInternal(type, fallbackSource)
            } catch (re: Exception) {
                Log.e("BaseCompositorNode", "Fallback shader compile also failed", re)
                throw e
            }
        }
    }

    protected fun drawQuad() {
        try {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)

            var posHandle = GLES20.glGetAttribLocation(programId, "aPosition")
            if (posHandle < 0) posHandle = GLES20.glGetAttribLocation(programId, "a_Position")
            var texHandle = GLES20.glGetAttribLocation(programId, "aTexCoord")
            if (texHandle < 0) texHandle = GLES20.glGetAttribLocation(programId, "a_TexCoord")

            if (posHandle >= 0) {
                GLES20.glEnableVertexAttribArray(posHandle)
                GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 4 * 4, 0)
            }
            if (texHandle >= 0) {
                GLES20.glEnableVertexAttribArray(texHandle)
                GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 4 * 4, 8)
            }

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            if (posHandle >= 0) GLES20.glDisableVertexAttribArray(posHandle)
            if (texHandle >= 0) GLES20.glDisableVertexAttribArray(texHandle)

            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        } catch (e: Exception) {
            Log.e("BaseCompositorNode", "drawQuad failed for $id", e)
            try {
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            } catch (_: Exception) {}
        }
    }

    override fun release() {
        try {
            if (programId != 0) {
                GLES20.glDeleteProgram(programId)
                programId = 0
            }
        } catch (e: Exception) {
            Log.w("BaseCompositorNode", "Delete program failed $id", e)
        }
        try {
            if (vboId != 0) {
                GLES20.glDeleteBuffers(1, intArrayOf(vboId), 0)
                vboId = 0
            }
        } catch (e: Exception) {
            Log.w("BaseCompositorNode", "Delete buffer failed $id", e)
        }
        try {
            if (vaoId != 0) {
                GLES30.glDeleteVertexArrays(1, intArrayOf(vaoId), 0)
                vaoId = 0
            }
        } catch (_: Exception) {}
        try {
            onRelease()
        } catch (e: Exception) {
            Log.w("BaseCompositorNode", "onRelease failed $id", e)
        }
        isInitialized = false
    }

    protected open fun onRelease() {}

    companion object {
        const val CAMERA_VERTEX_SHADER = "attribute vec4 aPosition;\n" +
"attribute vec2 aTexCoord;\n" +
"varying vec2 vTexCoord;\n" +
"void main() {\n" +
"  gl_Position = aPosition;\n" +
"  vTexCoord = aTexCoord;\n" +
"}\n"

        const val CAMERA_FRAGMENT_SHADER = "#extension GL_OES_EGL_image_external : require\n" +
"precision mediump float;\n" +
"varying vec2 vTexCoord;\n" +
"uniform samplerExternalOES sTexture;\n" +
"void main() {\n" +
"  gl_FragColor = texture2D(sTexture, vTexCoord);\n" +
"}\n"

        const val IMAGE_FRAGMENT_SHADER = "precision mediump float;\n" +
"varying vec2 vTexCoord;\n" +
"uniform sampler2D sTexture;\n" +
"void main() {\n" +
"  gl_FragColor = texture2D(sTexture, vTexCoord);\n" +
"}\n"

        const val VERTEX_SHADER = "attribute vec4 aPosition;\n" +
"attribute vec2 aTexCoord;\n" +
"varying vec2 vTexCoord;\n" +
"void main() {\n" +
"  gl_Position = aPosition;\n" +
"  vTexCoord = aTexCoord;\n" +
"}\n"

        const val VERTEX_SHADER_SIMPLE = "attribute vec4 aPosition;\n" +
"attribute vec2 aTexCoord;\n" +
"varying vec2 vTexCoord;\n" +
"void main() {\n" +
"  gl_Position = aPosition;\n" +
"  vTexCoord = aTexCoord;\n" +
"}\n"

        const val PASSTHROUGH_VERT = "attribute vec4 aPosition;\n" +
"attribute vec2 aTexCoord;\n" +
"varying vec2 vTexCoord;\n" +
"void main() {\n" +
"  gl_Position = aPosition;\n" +
"  vTexCoord = aTexCoord;\n" +
"}\n"

        const val PASSTHROUGH_FRAG = "precision mediump float;\n" +
"varying vec2 vTexCoord;\n" +
"uniform sampler2D sTexture;\n" +
"void main() {\n" +
"  gl_FragColor = texture2D(sTexture, vTexCoord);\n" +
"}\n"

        const val PASSTHROUGH_FRAG_OES = "#extension GL_OES_EGL_image_external : require\n" +
"precision mediump float;\n" +
"varying vec2 vTexCoord;\n" +
"uniform samplerExternalOES sTexture;\n" +
"void main() {\n" +
"  gl_FragColor = texture2D(sTexture, vTexCoord);\n" +
"}\n"

        const val PASSTHROUGH_FRAG_OES_ALT = "#extension GL_OES_EGL_image_external : require\n" +
"precision mediump float;\n" +
"varying vec2 vTexCoord;\n" +
"uniform samplerExternalOES sTexture;\n" +
"void main() {\n" +
"  gl_FragColor = texture2D(sTexture, vTexCoord);\n" +
"}\n"
    }
}

class CompositorCycleException(message: String, val cyclePath: List<String>) : RuntimeException(message)

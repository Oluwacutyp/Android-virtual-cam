package com.androidvirtualcam.compositor.nodes

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.Matrix
import android.util.Log
import com.androidvirtualcam.compositor.*

class CameraInputNode(
    override val id: String,
    override var parameters: NodeParameters = NodeParameters.builder()
        .int("width", 1280)
        .int("height", 720)
        .bool("mirrored", false)
        .float("rotation", 0f)
        .build()
) : BaseCompositorNode(
    id = id,
    type = "CameraInput",
    inputSockets = emptyList(),
    outputSocket = OutputSocket("output", "output", SocketType.TEXTURE),
    parameters = parameters
), CompositorGraph.MutableConnectionsNode {

    private var oesTexture: GLTexture? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var transformMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    private var mvpMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }

    private var width: Int = 1280
    private var height: Int = 720

    // Samsung S22 Ultra fix: SurfaceTexture updateTexImage() MUST be called on GL thread, not camera thread
    @Volatile
    var frameAvailable = false

    override fun onInitialize() {
        try {
            width = parameters.getInt("width", 1280)
            height = parameters.getInt("height", 720)

            oesTexture = GLTexture.createOes()

            surfaceTexture = SurfaceTexture(oesTexture!!.textureId).apply {
                setDefaultBufferSize(width, height)
                // Samsung fix: set listener to flag frame available, update on GL thread
                setOnFrameAvailableListener {
                    frameAvailable = true
                }
            }

            try {
                compileProgram(VERTEX_SHADER, FRAGMENT_SHADER_OES)
            } catch (e: Exception) {
                Log.e("CameraInputNode", "Shader compile failed, fallback to passthrough", e)
                try { compileProgram(PASSTHROUGH_VERT, PASSTHROUGH_FRAG_OES) } catch (_: Exception) { try { compileProgram(PASSTHROUGH_VERT, PASSTHROUGH_FRAG) } catch (_: Exception) {} }
            }

            Matrix.setIdentityM(transformMatrix, 0)
            Matrix.setIdentityM(mvpMatrix, 0)

            val mirrored = parameters.getBool("mirrored", false)
            if (mirrored) {
                Matrix.scaleM(mvpMatrix, 0, -1f, 1f, 1f)
            }

            val rotation = parameters.getFloat("rotation", 0f)
            if (rotation != 0f) {
                Matrix.rotateM(mvpMatrix, 0, rotation, 0f, 0f, 1f)
            }

            Log.d("CameraInputNode", "Initialized $id ${width}x${height} tex=${oesTexture?.textureId}")
        } catch (e: Exception) {
            Log.e("CameraInputNode", "onInitialize failed", e)
            throw e
        }
    }

    fun getSurfaceTexture(): SurfaceTexture? = surfaceTexture

    fun updateTransformMatrix(matrix: FloatArray) {
        try {
            System.arraycopy(matrix, 0, transformMatrix, 0, 16)
        } catch (e: Exception) {
            Log.w("CameraInputNode", "updateTransformMatrix failed", e)
        }
    }

    fun getTexture(): GLTexture? = oesTexture

    private var isSharedTexture = false

    fun shareTextureFrom(other: CameraInputNode) {
        try {
            if (oesTexture?.isOwned == true) {
                oesTexture?.release()
            }
        } catch (_: Exception) {}
        val otherTex = other.getTexture()
        if (otherTex != null) {
            oesTexture = GLTexture.wrapExisting(otherTex.textureId, otherTex.target, width, height, owned = false)
        }
        surfaceTexture = other.getSurfaceTexture()
        isSharedTexture = true
        Log.d("CameraInputNode", "Shared texture from ${other.id} to $id tex=${oesTexture?.textureId}")
    }

    private var frameCount = 0
    private var lastLogTime = 0L
    private var firstFrameTime = 0L
    private var hasLoggedNoFrame = false

    override fun process(inputs: Map<String, GLTexture>): GLTexture {
        try {
            check(isInitialized) { "Node not initialized" }
            val tex = oesTexture ?: throw IllegalStateException("OES texture not created")

            if (firstFrameTime == 0L) firstFrameTime = System.currentTimeMillis()

            if (!isSharedTexture) {
                try {
                    // Samsung S22 Ultra fix: updateTexImage MUST be called on GL thread, check frameAvailable flag
                    if (frameAvailable) {
                        surfaceTexture?.updateTexImage()
                        frameAvailable = false
                    } else {
                        // Still try to update if first frame or if no frameAvailable tracking (fallback)
                        // On some devices listener may not fire, so try update anyway but catch exception
                        try {
                            surfaceTexture?.updateTexImage()
                        } catch (_: Exception) {
                            // If no new frame, keep previous texture
                        }
                    }
                    surfaceTexture?.getTransformMatrix(transformMatrix)
                    frameCount++
                    val now = System.currentTimeMillis()
                    if (now - lastLogTime > 3000) {
                        Log.d("CameraInputNode", "Pipeline OK: CameraX -> OES texId=${tex.textureId} frame=$frameCount frameAvailable=$frameAvailable")
                        lastLogTime = now
                    }
                    if (frameCount == 1) {
                        Log.i("CameraInputNode", "First camera frame received! Pipeline working (GL thread update)")
                    }
                } catch (e: Exception) {
                    val elapsed = System.currentTimeMillis() - firstFrameTime
                    if (elapsed > 3000 && !hasLoggedNoFrame) {
                        Log.w("CameraInputNode", "No camera frames after ${elapsed}ms – black camera! surfaceTexture=${surfaceTexture != null} texId=${tex.textureId}")
                        hasLoggedNoFrame = true
                    }
                    if (elapsed > 3000 && frameCount == 0) {
                        return getTestPatternTexture()
                    }
                }
            } else {
                try {
                    // For shared texture, also respect frameAvailable but still get matrix
                    if (frameAvailable) {
                        frameAvailable = false
                    }
                    surfaceTexture?.getTransformMatrix(transformMatrix)
                    frameCount++
                } catch (e: Exception) {
                    Log.w("CameraInputNode", "Shared mode getTransformMatrix failed", e)
                }
            }

            val mirrored = parameters.getBool("mirrored", false)
            val rotation = parameters.getFloat("rotation", 0f)
            val hasTransform = mirrored || rotation != 0f || !isIdentity(transformMatrix)

            if (!hasTransform) {
                return GLTexture.wrapExisting(tex.textureId, tex.target, width, height, owned = false)
            }

            val fbo = framebuffer ?: GLFramebuffer.create(width, height).also { framebuffer = it }
            fbo.bind()

            GLES20.glUseProgram(programId)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex.textureId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "sTexture"), 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(programId, "uMVP"), 1, false, mvpMatrix, 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(programId, "uTexMatrix"), 1, false, transformMatrix, 0)

            drawQuad()

            fbo.unbind()

            return GLTexture.wrapExisting(fbo.texture.textureId, fbo.texture.target, width, height, owned = false)
        } catch (e: Exception) {
            Log.e("CameraInputNode", "process failed", e)
            return try {
                getTestPatternTexture()
            } catch (re: Exception) {
                Log.e("CameraInputNode", "Test pattern fallback also failed", re)
                GLTexture.wrapExisting(oesTexture!!.textureId, oesTexture!!.target, width, height, owned = false)
            }
        }
    }

    private var framebuffer: GLFramebuffer? = null
    private var testPatternFramebuffer: GLFramebuffer? = null
    private var testPatternProgram: Int = 0

    private fun getTestPatternTexture(): GLTexture {
        try {
            if (testPatternFramebuffer == null || testPatternFramebuffer!!.width != width || testPatternFramebuffer!!.height != height) {
                testPatternFramebuffer?.close()
                testPatternFramebuffer = GLFramebuffer.create(width, height)
            }
            if (testPatternProgram == 0) {
                testPatternProgram = compileProgram(VERTEX_SHADER_SIMPLE, FRAGMENT_SHADER_TEST_PATTERN)
            }
            val fbo = testPatternFramebuffer!!
            fbo.bind()
            GLES20.glViewport(0, 0, width, height)
            GLES20.glUseProgram(testPatternProgram)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(testPatternProgram, "uTime"), (System.currentTimeMillis() % 10000).toFloat() / 1000f)
            drawQuadWithProgram(testPatternProgram)
            fbo.unbind()
            return GLTexture.wrapExisting(fbo.texture.textureId, fbo.texture.target, width, height, owned = false)
        } catch (e: Exception) {
            Log.e("CameraInputNode", "Failed to create test pattern fallback", e)
            return GLTexture.wrapExisting(oesTexture!!.textureId, oesTexture!!.target, width, height, owned = false)
        }
    }

    private fun drawQuadWithProgram(prog: Int) {
        try {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)
            val posHandle = run { var h = GLES20.glGetAttribLocation(prog, "aPosition"); if (h < 0) h = GLES20.glGetAttribLocation(prog, "a_Position"); h }
            val texHandle = run { var h = GLES20.glGetAttribLocation(prog, "aTexCoord"); if (h < 0) h = GLES20.glGetAttribLocation(prog, "a_TexCoord"); h }
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
            Log.e("CameraInputNode", "drawQuadWithProgram failed", e)
            try { GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0) } catch (_: Exception) {}
        }
    }

    private fun isIdentity(matrix: FloatArray): Boolean {
        val identity = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
        return matrix.contentEquals(identity)
    }

    override fun onRelease() {
        try { surfaceTexture?.release() } catch (_: Exception) {}
        surfaceTexture = null
        try { oesTexture?.release() } catch (_: Exception) {}
        oesTexture = null
        try { framebuffer?.close() } catch (_: Exception) {}
        framebuffer = null
        try { testPatternFramebuffer?.close() } catch (_: Exception) {}
        testPatternFramebuffer = null
        if (testPatternProgram != 0) {
            try { GLES20.glDeleteProgram(testPatternProgram) } catch (_: Exception) {}
            testPatternProgram = 0
        }
    }

    override fun setInputConnection(inputSocketId: String, fromNodeId: String?, fromOutputId: String?) {}

    companion object {
        private const val VERTEX_SHADER = "attribute vec4 aPosition;\n" +
"attribute vec2 aTexCoord;\n" +
"varying vec2 vTexCoord;\n" +
"void main() {\n" +
"  gl_Position = aPosition;\n" +
"  vTexCoord = aTexCoord;\n" +
"}\n"

        private const val VERTEX_SHADER_SIMPLE = "attribute vec4 aPosition;\n" +
"attribute vec2 aTexCoord;\n" +
"varying vec2 vTexCoord;\n" +
"void main() {\n" +
"  gl_Position = aPosition;\n" +
"  vTexCoord = aTexCoord;\n" +
"}\n"

        private const val FRAGMENT_SHADER_TEST_PATTERN = "precision mediump float;\n" +
"varying vec2 vTexCoord;\n" +
"uniform float uTime;\n" +
"void main() {\n" +
"  float x = vTexCoord.x;\n" +
"  vec3 color;\n" +
"  if (x < 0.14) color = vec3(0.5, 0.5, 0.5);\n" +
"  else if (x < 0.28) color = vec3(1.0, 1.0, 0.0);\n" +
"  else if (x < 0.42) color = vec3(0.0, 1.0, 1.0);\n" +
"  else if (x < 0.56) color = vec3(0.0, 1.0, 0.0);\n" +
"  else if (x < 0.70) color = vec3(1.0, 0.0, 1.0);\n" +
"  else color = vec3(0.0, 0.0, 1.0);\n" +
"  gl_FragColor = vec4(color, 1.0);\n" +
"}\n"

        private const val FRAGMENT_SHADER_OES = "#extension GL_OES_EGL_image_external : require\n" +
"precision mediump float;\n" +
"varying vec2 vTexCoord;\n" +
"uniform samplerExternalOES sTexture;\n" +
"void main() {\n" +
"  gl_FragColor = texture2D(sTexture, vTexCoord);\n" +
"}\n"
    }
}

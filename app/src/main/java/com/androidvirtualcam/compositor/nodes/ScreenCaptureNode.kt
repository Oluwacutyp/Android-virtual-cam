package com.androidvirtualcam.compositor.nodes

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import com.androidvirtualcam.compositor.*

/**
 * ScreenCaptureNode – captures phone screen via MediaProjection VirtualDisplay -> OES texture.
 * FIXED: All shaders #version 100 for Camon 20 / S22 Ultra
 */
class ScreenCaptureNode(
    override val id: String,
    override var parameters: NodeParameters = NodeParameters.builder()
        .int("width", 1280)
        .int("height", 720)
        .int("dpi", 320)
        .bool("mirrored", false)
        .float("rotation", 0f)
        .bool("showCursor", true)
        .bool("isCapturing", false)
        .build()
) : BaseCompositorNode(
    id = id,
    type = "ScreenCapture",
    inputSockets = emptyList(),
    outputSocket = OutputSocket("output", "output", SocketType.TEXTURE),
    parameters = parameters
), CompositorGraph.MutableConnectionsNode {

    private var oesTexture: GLTexture? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    private var transformMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    private var mvpMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }

    private var width: Int = 1280
    private var height: Int = 720
    private var dpi: Int = 320

    private var framebuffer: GLFramebuffer? = null
    private var placeholderFramebuffer: GLFramebuffer? = null

    private var frameAvailable = false
    private var lastUpdateNs = 0L
    private var frameCount = 0

    @Volatile
    private var isCapturingState = false

    override fun onInitialize() {
        try {
            width = parameters.getInt("width", 1280)
            height = parameters.getInt("height", 720)
            dpi = parameters.getInt("dpi", 320)

            oesTexture = GLTexture.createOes()

            surfaceTexture = SurfaceTexture(oesTexture!!.textureId).apply {
                setDefaultBufferSize(width, height)
                setOnFrameAvailableListener {
                    synchronized(this@ScreenCaptureNode) {
                        frameAvailable = true
                    }
                }
            }

            surface = Surface(surfaceTexture)

            try {
                compileProgram(VERTEX_SHADER, FRAGMENT_SHADER_OES)
            } catch (e: Exception) {
                Log.e("ScreenCaptureNode", "Shader compile failed, fallback to passthrough", e)
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

            placeholderFramebuffer = GLFramebuffer.create(width, height)

            Log.d("ScreenCaptureNode", "Initialized $id ${width}x${height} dpi=$dpi tex=${oesTexture?.textureId}")
        } catch (e: Exception) {
            Log.e("ScreenCaptureNode", "onInitialize failed", e)
            throw e
        }
    }

    fun getSurface(): Surface? = surface
    fun getSurfaceTexture(): SurfaceTexture? = surfaceTexture
    fun getTexture(): GLTexture? = oesTexture

    fun setCapturing(capturing: Boolean) {
        isCapturingState = capturing
        parameters = parameters.withBool("isCapturing", capturing)
        Log.d("ScreenCaptureNode", "setCapturing $id = $capturing")
    }

    fun isCapturing(): Boolean = isCapturingState
    fun getWidth(): Int = width
    fun getHeight(): Int = height
    fun getDpi(): Int = dpi

    fun updateSize(newWidth: Int, newHeight: Int, newDpi: Int = dpi) {
        if (newWidth == width && newHeight == height && newDpi == dpi) return
        width = newWidth
        height = newHeight
        dpi = newDpi
        parameters = parameters.withInt("width", width).withInt("height", height).withInt("dpi", dpi)
        try {
            surfaceTexture?.setDefaultBufferSize(width, height)
        } catch (e: Exception) {
            Log.w("ScreenCaptureNode", "setDefaultBufferSize failed", e)
        }
        try {
            framebuffer?.close()
            framebuffer = null
            placeholderFramebuffer?.close()
            placeholderFramebuffer = GLFramebuffer.create(width, height)
        } catch (e: Exception) {
            Log.w("ScreenCaptureNode", "FBO recreate failed", e)
        }
    }

    override fun process(inputs: Map<String, GLTexture>): GLTexture {
        try {
            check(isInitialized) { "Node not initialized" }
            val tex = oesTexture ?: throw IllegalStateException("OES texture not created")

            if (!isCapturingState) {
                return getPlaceholderTexture()
            }

            var updated = false
            synchronized(this) {
                if (frameAvailable) {
                    frameAvailable = false
                    updated = true
                }
            }

            try {
                surfaceTexture?.updateTexImage()
                surfaceTexture?.getTransformMatrix(transformMatrix)
                if (updated) {
                    frameCount++
                    lastUpdateNs = System.nanoTime()
                }
            } catch (e: Exception) {
                if (frameCount % 60 == 0) {
                    Log.w("ScreenCaptureNode", "updateTexImage failed (no frame yet) $id", e)
                }
                try {
                    surfaceTexture?.getTransformMatrix(transformMatrix)
                } catch (_: Exception) {}
            }

            val fbo = framebuffer ?: GLFramebuffer.create(width, height).also { framebuffer = it }

            if (fbo.width != width || fbo.height != height) {
                fbo.close()
                val newFbo = GLFramebuffer.create(width, height)
                framebuffer = newFbo
                return process(inputs)
            }

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
            Log.e("ScreenCaptureNode", "process failed", e)
            return getPlaceholderTexture()
        }
    }

    private fun getPlaceholderTexture(): GLTexture {
        try {
            val phFbo = placeholderFramebuffer ?: GLFramebuffer.create(width, height).also { placeholderFramebuffer = it }
            phFbo.bind()
            GLES20.glClearColor(0.1f, 0.1f, 0.12f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            phFbo.unbind()
            return GLTexture.wrapExisting(phFbo.texture.textureId, phFbo.texture.target, width, height, owned = false)
        } catch (e: Exception) {
            Log.e("ScreenCaptureNode", "getPlaceholderTexture failed", e)
            return GLTexture.wrapExisting(oesTexture!!.textureId, oesTexture!!.target, width, height, owned = false)
        }
    }

    private fun isIdentity(matrix: FloatArray): Boolean {
        val identity = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
        return matrix.contentEquals(identity)
    }

    override fun onRelease() {
        try { surface?.release() } catch (_: Exception) {}
        surface = null
        try { surfaceTexture?.release() } catch (_: Exception) {}
        surfaceTexture = null
        try { oesTexture?.release() } catch (_: Exception) {}
        oesTexture = null
        try { framebuffer?.close() } catch (_: Exception) {}
        framebuffer = null
        try { placeholderFramebuffer?.close() } catch (_: Exception) {}
        placeholderFramebuffer = null
        isCapturingState = false
        frameCount = 0
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

        private const val FRAGMENT_SHADER_OES = "#extension GL_OES_EGL_image_external : require\n" +
"precision mediump float;\n" +
"varying vec2 vTexCoord;\n" +
"uniform samplerExternalOES sTexture;\n" +
"void main() {\n" +
"  gl_FragColor = texture2D(sTexture, vTexCoord);\n" +
"}\n"
    }
}

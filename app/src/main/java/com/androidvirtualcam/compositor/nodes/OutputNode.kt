package com.androidvirtualcam.compositor.nodes

import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import com.androidvirtualcam.compositor.*
import com.androidvirtualcam.framebus.VCamFrameBus
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OutputNode(
    override val id: String,
    override var parameters: NodeParameters = NodeParameters.builder()
        .int("width", 1280)
        .int("height", 720)
        .build()
) : BaseCompositorNode(
    id = id,
    type = "Output",
    inputSockets = listOf(
        InputSocket("input", "input", SocketType.TEXTURE, displayName = "Input")
    ),
    outputSocket = OutputSocket("output", "output", SocketType.TEXTURE),
    parameters = parameters
), CompositorGraph.MutableConnectionsNode {

    private var framebuffer: GLFramebuffer? = null
    private var isOesInput: Boolean = false

    var frameBus: VCamFrameBus? = null

    override fun onInitialize() {
        try {
            compileProgram(VERTEX_SHADER, FRAGMENT_SHADER_COPY)
        } catch (e: Exception) {
            Log.e("OutputNode", "Shader compile failed", e)
            try { compileProgram(PASSTHROUGH_VERT, PASSTHROUGH_FRAG) } catch (_: Exception) {}
        }
        Log.d("OutputNode", "Initialized $id")
    }

    override fun process(inputs: Map<String, GLTexture>): GLTexture {
        check(isInitialized) { "Node not initialized" }
        val input = inputs["input"] ?: throw IllegalArgumentException("OutputNode $id requires input")

        isOesInput = input.isOes

        if (framebuffer == null || framebuffer!!.width != input.width || framebuffer!!.height != input.height) {
            framebuffer?.close()
            framebuffer = GLFramebuffer.create(input.width, input.height)
        }

        val fbo = framebuffer!!
        fbo.bind()

        if (isOesInput) {
            try {
                val oesProgram = compileProgram(VERTEX_SHADER, FRAGMENT_SHADER_OES_TO_2D)
                GLES20.glUseProgram(oesProgram)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(input.target, input.textureId)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(oesProgram, "sTexture"), 0)
                drawQuadWithProgram(oesProgram)
            } catch (e: Exception) {
                Log.e("OutputNode", "OES to 2D failed, fallback", e)
                GLES20.glUseProgram(programId)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(input.target, input.textureId)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "sTexture"), 0)
                drawQuad()
            }
        } else {
            GLES20.glUseProgram(programId)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(input.target, input.textureId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "sTexture"), 0)
            drawQuad()
        }

        // Publish to frameBus if available
        try {
            frameBus?.let { bus ->
                val w = fbo.width
                val h = fbo.height
                val rgbaBuffer = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
                GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rgbaBuffer)
                val error = GLES20.glGetError()
                if (error == GLES20.GL_NO_ERROR) {
                    val nv21 = ByteBuffer.allocateDirect(w * h * 3 / 2).order(ByteOrder.nativeOrder())
                    convertRgbaToNv21(rgbaBuffer, nv21, w, h)
                    bus.publishFrame(nv21, System.nanoTime(), w, h)
                }
            }
        } catch (e: Exception) {
            Log.w("OutputNode", "FrameBus publish failed", e)
        }

        fbo.unbind()

        return GLTexture.wrapExisting(fbo.texture.textureId, fbo.texture.target, fbo.width, fbo.height, owned = false)
    }

    private fun convertRgbaToNv21(rgba: ByteBuffer, nv21: ByteBuffer, width: Int, height: Int) {
        rgba.position(0)
        val ySize = width * height
        val row = ByteArray(width * 4)
        for (y in 0 until height) {
            val srcY = height - 1 - y
            rgba.position(srcY * width * 4)
            rgba.get(row, 0, width * 4)
            for (x in 0 until width) {
                val r = row[x * 4].toInt() and 0xFF
                val g = row[x * 4 + 1].toInt() and 0xFF
                val b = row[x * 4 + 2].toInt() and 0xFF
                val yVal = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                nv21.put(y * width + x, yVal.coerceIn(0, 255).toByte())
            }
        }
        var uvIndex = ySize
        for (y in 0 until height step 2) {
            val srcY = height - 1 - y
            for (x in 0 until width step 2) {
                val pos = srcY * width * 4 + x * 4
                rgba.position(pos)
                val r = rgba.get().toInt() and 0xFF
                val g = rgba.get().toInt() and 0xFF
                val b = rgba.get().toInt() and 0xFF
                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                if (uvIndex + 1 < nv21.capacity()) {
                    nv21.put(uvIndex++, v.coerceIn(0, 255).toByte())
                    nv21.put(uvIndex++, u.coerceIn(0, 255).toByte())
                }
            }
        }
    }

    private fun drawQuadWithProgram(prog: Int) {
        try {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)
            var posHandle = GLES20.glGetAttribLocation(prog, "aPosition")
            if (posHandle < 0) posHandle = GLES20.glGetAttribLocation(prog, "a_Position")
            var texHandle = GLES20.glGetAttribLocation(prog, "aTexCoord")
            if (texHandle < 0) texHandle = GLES20.glGetAttribLocation(prog, "a_TexCoord")
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
            Log.e("OutputNode", "drawQuadWithProgram failed", e)
            try { GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0) } catch (_: Exception) {}
        }
    }

    override fun onRelease() {
        framebuffer?.close()
        framebuffer = null
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

        private const val FRAGMENT_SHADER_COPY = "precision mediump float;\n" +
"varying vec2 vTexCoord;\n" +
"uniform sampler2D sTexture;\n" +
"void main() {\n" +
"  gl_FragColor = texture2D(sTexture, vTexCoord);\n" +
"}\n"

        private const val FRAGMENT_SHADER_OES_TO_2D = "#extension GL_OES_EGL_image_external : require\n" +
"precision mediump float;\n" +
"varying vec2 vTexCoord;\n" +
"uniform samplerExternalOES sTexture;\n" +
"void main() {\n" +
"  gl_FragColor = texture2D(sTexture, vTexCoord);\n" +
"}\n"
    }
}

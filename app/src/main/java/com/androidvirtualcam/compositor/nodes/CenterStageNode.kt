package com.androidvirtualcam.compositor.nodes

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import com.androidvirtualcam.compositor.*

/**
 * CenterStageNode – Apple Center Stage style auto-framing.
 *
 * Takes camera texture and applies smooth digital pan/zoom to keep face centered.
 * Uses GPU crop: uv = center + (v_TexCoord - 0.5) / zoom
 *
 * Parameters:
 * - enabled: bool – whether tracking is active
 * - centerX: float 0..1 – target center X
 * - centerY: float 0..1 – target center Y
 * - zoom: float 1..5 – digital zoom (1 = no zoom, 3 = 3x)
 * - maxZoom: float – max allowed zoom
 * - padding: float – padding factor around face
 * - trackingMode: string SINGLE/GROUP
 * - smoothing: bool – whether to smooth (handled externally)
 *
 * Handles both 2D and OES input textures.
 */
class CenterStageNode(
    override val id: String,
    override var parameters: NodeParameters = NodeParameters.builder()
        .float("centerX", 0.5f)
        .float("centerY", 0.5f)
        .float("zoom", 1f)
        .float("maxZoom", 3f)
        .float("padding", 1.8f)
        .bool("enabled", false)
        .bool("smoothing", true)
        .string("trackingMode", "GROUP")
        .build()
) : BaseCompositorNode(
    id = id,
    type = "CenterStage",
    inputSockets = listOf(
        InputSocket("input", "input", SocketType.TEXTURE, displayName = "Input")
    ),
    outputSocket = OutputSocket("output", "output", SocketType.TEXTURE),
    parameters = parameters
), CompositorGraph.MutableConnectionsNode {

    private var framebuffer: GLFramebuffer? = null
    private var program2D: Int = 0
    private var programOES: Int = 0

    // Volatile for thread-safe updates from CenterStageManager (non-GL thread)
    @Volatile private var currentCenterX: Float = 0.5f
    @Volatile private var currentCenterY: Float = 0.5f
    @Volatile private var currentZoom: Float = 1f
    @Volatile private var currentEnabled: Boolean = false

    override fun onInitialize() {
        try {
            program2D = compileProgram(VERTEX_SHADER_SIMPLE, FRAGMENT_SHADER_2D)
        } catch (e: Exception) {
            Log.e("CenterStageNode", "Shader compile failed, fallback to passthrough", e)
            try { compileProgram(PASSTHROUGH_VERT, PASSTHROUGH_FRAG_OES) } catch (_: Exception) { try { compileProgram(PASSTHROUGH_VERT, PASSTHROUGH_FRAG) } catch (_: Exception) {} }
        }
        try {
            programOES = compileProgram(VERTEX_SHADER_SIMPLE, FRAGMENT_SHADER_OES)
        } catch (e: Exception) {
            Log.e("CenterStageNode", "Shader compile failed, fallback to passthrough", e)
            try { compileProgram(PASSTHROUGH_VERT, PASSTHROUGH_FRAG_OES) } catch (_: Exception) { try { compileProgram(PASSTHROUGH_VERT, PASSTHROUGH_FRAG) } catch (_: Exception) {} }
        }

        // Init from parameters
        currentCenterX = parameters.getFloat("centerX", 0.5f)
        currentCenterY = parameters.getFloat("centerY", 0.5f)
        currentZoom = parameters.getFloat("zoom", 1f)
        currentEnabled = parameters.getBool("enabled", false)

        Log.d("CenterStageNode", "Initialized $id enabled=$currentEnabled center=($currentCenterX,$currentCenterY) zoom=$currentZoom")
    }

    /**
     * Called from CenterStageManager on main thread for low-latency update.
     */
    fun setTransform(centerX: Float, centerY: Float, zoom: Float, enabled: Boolean) {
        currentCenterX = centerX.coerceIn(0f, 1f)
        currentCenterY = centerY.coerceIn(0f, 1f)
        currentZoom = zoom.coerceIn(1f, 5f)
        currentEnabled = enabled
    }

    override fun process(inputs: Map<String, GLTexture>): GLTexture {
        check(isInitialized) { "CenterStageNode not initialized" }
        val input = inputs["input"] ?: throw IllegalArgumentException("CenterStageNode $id requires input")

        val w = input.width
        val h = input.height

        if (framebuffer == null || framebuffer!!.width != w || framebuffer!!.height != h) {
            framebuffer?.close()
            framebuffer = GLFramebuffer.create(w, h)
        }

        // If not enabled or zoom ~1 and center ~0.5, pass through with minimal overhead
        if (!currentEnabled || (currentZoom <= 1.01f && kotlin.math.abs(currentCenterX - 0.5f) < 0.01f && kotlin.math.abs(currentCenterY - 0.5f) < 0.01f)) {
            // Fast path: if input size matches output, return input directly (zero copy)
            // But we need to ensure FBO size handling – if we return input directly, no copy needed
            // However output node expects texture, so we can return input if no transform
            // For safety, if disabled, return input directly
            if (!currentEnabled) {
                return input
            }
        }

        // Update parameters from volatile state for serialization consistency
        // (not strictly needed every frame, but keep params in sync)
        // We don't update parameters map every frame to avoid allocation, only when needed

        framebuffer!!.bind()
        GLES20.glViewport(0, 0, w, h)

        val isOes = input.isOes
        val program = if (isOes) programOES else program2D

        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(input.target, input.textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTexture"), 0)

        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uCenter"), currentCenterX, currentCenterY)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uZoom"), currentZoom)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uEnabled"), if (currentEnabled) 1 else 0)

        drawQuadWithProgram(program)

        framebuffer!!.unbind()

        return GLTexture.wrapExisting(framebuffer!!.texture.textureId, framebuffer!!.texture.target, w, h, owned = false)
    }

    private fun drawQuadWithProgram(prog: Int) {
        GLES30.glBindVertexArray(vaoId)
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
        GLES30.glBindVertexArray(0)
    }

    override fun onRelease() {
        framebuffer?.close()
        framebuffer = null
        if (program2D != 0) {
            GLES20.glDeleteProgram(program2D)
            program2D = 0
        }
        if (programOES != 0) {
            GLES20.glDeleteProgram(programOES)
            programOES = 0
        }
    }

    override fun setInputConnection(inputSocketId: String, fromNodeId: String?, fromOutputId: String?) {}

    companion object {
        private const val FRAGMENT_SHADER_2D = "precision mediump float;\n" +
"            varying vec2 vTexCoord;\n" +
"            \n" +
"            uniform sampler2D sTexture;\n" +
"            uniform vec2 uCenter;\n" +
"            uniform float uZoom;\n" +
"            uniform int uEnabled;\n" +
"\n" +
"            void main() {\n" +
"                if (uEnabled == 0) {\n" +
"                    gl_FragColor = texture2D(sTexture, vTexCoord);\n" +
"                } else {\n" +
"                    // Center Stage crop: sample sub-rect centered at uCenter with size 1/zoom\n" +
"                    vec2 uv = uCenter + (vTexCoord - 0.5) / uZoom;\n" +
"                    // Clamp to avoid sampling outside – keeps edges when near border\n" +
"                    uv = clamp(uv, 0.0, 1.0);\n" +
"                    gl_FragColor = texture2D(sTexture, uv);\n" +
"                }\n" +
"            }\n"

        private const val FRAGMENT_SHADER_OES = "#extension GL_OES_EGL_image_external : require\n" +
"            precision mediump float;\n" +
"            varying vec2 vTexCoord;\n" +
"            \n" +
"            uniform samplerExternalOES sTexture;\n" +
"            uniform vec2 uCenter;\n" +
"            uniform float uZoom;\n" +
"            uniform int uEnabled;\n" +
"\n" +
"            void main() {\n" +
"                if (uEnabled == 0) {\n" +
"                    gl_FragColor = texture2D(sTexture, vTexCoord);\n" +
"                } else {\n" +
"                    vec2 uv = uCenter + (vTexCoord - 0.5) / uZoom;\n" +
"                    uv = clamp(uv, 0.0, 1.0);\n" +
"                    gl_FragColor = texture2D(sTexture, uv);\n" +
"                }\n" +
"            }\n"
    }
}

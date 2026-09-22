package com.androidvirtualcam.compositor.nodes

import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import com.androidvirtualcam.compositor.*

/**
 * ChromaKeyNode – GLSL with keyColor, threshold, smoothing, AND spill suppression.
 * Production: removes green fringing on edges via spill suppression.
 *
 * Inputs:
 * - input: texture2D(foreground with green screen)
 *
 * Parameters:
 * - keyColor: vec3 (0..1) – green by default (0,1,0)
 * - threshold: float 0..1 – distance threshold
 * - slope: float 0..1 – edge smoothing
 * - spill: float 0..1 – spill suppression strength
 */
class ChromaKeyNode(
    override val id: String,
    override var parameters: NodeParameters = NodeParameters.builder()
        .vec3("keyColor", 0f, 1f, 0f)
        .float("threshold", 0.4f)
        .float("slope", 0.1f)
        .float("spill", 0.5f)
        .build()
) : BaseCompositorNode(
    id = id,
    type = "ChromaKey",
    inputSockets = listOf(
        InputSocket("input", "input", SocketType.TEXTURE, displayName = "Foreground")
    ),
    outputSocket = OutputSocket("output", "output", SocketType.TEXTURE),
    parameters = parameters
), CompositorGraph.MutableConnectionsNode {

    private var framebuffer: GLFramebuffer? = null
    private var width: Int = 1280
    private var height: Int = 720

    override fun onInitialize() {
        try {
            compileProgram(VERTEX_SHADER_SIMPLE, FRAGMENT_SHADER_CHROMA)
        } catch (e: Exception) {
            Log.e("ChromaKeyNode", "Shader compile failed, fallback to passthrough", e)
            try { compileProgram(PASSTHROUGH_VERT, PASSTHROUGH_FRAG) } catch (_: Exception) {}
        }

        // Get initial size from parameters or default
        width = parameters.getInt("width", 1280)
        height = parameters.getInt("height", 720)

        framebuffer = GLFramebuffer.create(width, height)

        Log.d("ChromaKeyNode", "Initialized $id with keyColor=${parameters.getVec3("keyColor")}")
    }

    override fun process(inputs: Map<String, GLTexture>): GLTexture {
        check(isInitialized) { "Node not initialized" }
        val input = inputs["input"] ?: throw IllegalArgumentException("ChromaKeyNode $id requires input texture")

        // Resize FBO if needed
        if (framebuffer == null || framebuffer!!.width != input.width || framebuffer!!.height != input.height) {
            framebuffer?.close()
            framebuffer = GLFramebuffer.create(input.width, input.height)
            width = input.width
            height = input.height
        }

        val fbo = framebuffer!!

        fbo.bind()
        GLES20.glUseProgram(programId)

        // Bind input texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(input.target, input.textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "sTexture"), 0)

        // Set uniforms
        val keyColor = parameters.getVec3("keyColor", listOf(0f, 1f, 0f))
        GLES20.glUniform3f(GLES20.glGetUniformLocation(programId, "uKeyColor"), keyColor[0], keyColor[1], keyColor[2])
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, "uThreshold"), parameters.getFloat("threshold", 0.4f))
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, "uSlope"), parameters.getFloat("slope", 0.1f))
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, "uSpill"), parameters.getFloat("spill", 0.5f))

        drawQuad()

        fbo.unbind()

        return GLTexture.wrapExisting(fbo.texture.textureId, fbo.texture.target, width, height, owned = false)
    }

    override fun onRelease() {
        framebuffer?.close()
        framebuffer = null
    }

    override fun setInputConnection(inputSocketId: String, fromNodeId: String?, fromOutputId: String?) {
        // Connections handled by graph
    }

    companion object {
        // Production chroma key with spill suppression – removes green fringing on edges
        private const val FRAGMENT_SHADER_CHROMA = "precision mediump float;\n" +
"            varying vec2 vTexCoord;\n" +
"            \n" +
"            uniform sampler2D sTexture;\n" +
"            uniform vec3 uKeyColor;\n" +
"            uniform float uThreshold;\n" +
"            uniform float uSlope;\n" +
"            uniform float uSpill;\n" +
"\n" +
"            // Spill suppression: removes green spill from edges\n" +
"            // Based on spill map and desaturation\n" +
"            vec3 spillSuppress(vec3 color, vec3 keyColor, float spillStrength) {\n" +
"                // Calculate spill amount – how much green exceeds other channels\n" +
"                float spill = max(0.0, color.g - max(color.r, color.b));\n" +
"                // Suppress green channel proportionally to spill\n" +
"                float suppress = spill * spillStrength;\n" +
"                color.g = max(color.g - suppress, max(color.r, color.b));\n" +
"                // Also desaturate slightly in spill areas to remove green tint\n" +
"                float gray = dot(color, vec3(0.299, 0.587, 0.114));\n" +
"                color = mix(color, vec3(gray), spill * 0.2 * spillStrength);\n" +
"                return color;\n" +
"            }\n" +
"\n" +
"            void main() {\n" +
"                vec4 src = texture2D(sTexture, vTexCoord);\n" +
"                vec3 color = src.rgb;\n" +
"\n" +
"                // Distance from key color\n" +
"                float dist = distance(color, uKeyColor);\n" +
"\n" +
"                // Alpha from chroma key – smoothstep for soft edges\n" +
"                float alpha = smoothstep(uThreshold, uThreshold + uSlope, dist);\n" +
"\n" +
"                // Spill suppression\n" +
"                vec3 suppressed = spillSuppress(color, uKeyColor, uSpill);\n" +
"\n" +
"                // For pixels that are partially transparent (near key), apply more suppression\n" +
"                float spillFactor = (1.0 - alpha) * uSpill;\n" +
"                suppressed = spillSuppress(suppressed, uKeyColor, spillFactor);\n" +
"\n" +
"                gl_FragColor = vec4(suppressed, src.a * alpha);\n" +
"\n" +
"                // Discard fully transparent pixels for performance (optional, but we keep alpha for blending)\n" +
"                if (gl_FragColor.a < 0.01) {\n" +
"                    // Keep transparent but don't discard to allow blending with background\n" +
"                    gl_FragColor.a = 0.0;\n" +
"                }\n" +
"            }\n"
    }
}

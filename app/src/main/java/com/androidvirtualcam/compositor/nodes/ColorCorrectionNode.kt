package com.androidvirtualcam.compositor.nodes

import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import com.androidvirtualcam.compositor.*

/**
 * ColorCorrectionNode – lift/gamma/gain per channel + HSL + contrast/brightness.
 *
 * Inputs:
 * - input: texture
 *
 * Parameters:
 * - lift: vec3 (shadows)
 * - gamma: vec3 (midtones) – 0.1..4.0
 * - gain: vec3 (highlights)
 * - saturation: float 0..2
 * - hueShift: float -1..1 (degrees/360)
 * - brightness: float -1..1
 * - contrast: float 0..2
 * - temperature: float -1..1 (warm/cool)
 */
class ColorCorrectionNode(
    override val id: String,
    override var parameters: NodeParameters = NodeParameters.builder()
        .vec3("lift", 0f, 0f, 0f)
        .vec3("gamma", 1f, 1f, 1f)
        .vec3("gain", 1f, 1f, 1f)
        .float("saturation", 1f)
        .float("hueShift", 0f)
        .float("brightness", 0f)
        .float("contrast", 1f)
        .float("temperature", 0f)
        .build()
) : BaseCompositorNode(
    id = id,
    type = "ColorCorrection",
    inputSockets = listOf(
        InputSocket("input", "input", SocketType.TEXTURE, displayName = "Input")
    ),
    outputSocket = OutputSocket("output", "output", SocketType.TEXTURE),
    parameters = parameters
), CompositorGraph.MutableConnectionsNode {

    private var framebuffer: GLFramebuffer? = null

    override fun onInitialize() {
        try {
            compileProgram(VERTEX_SHADER_SIMPLE, FRAGMENT_SHADER_CC)
        } catch (e: Exception) {
            Log.e("ColorCorrectionNode", "Shader compile failed, fallback to passthrough", e)
            try { compileProgram(PASSTHROUGH_VERT, PASSTHROUGH_FRAG) } catch (_: Exception) {}
        }
        Log.d("ColorCorrectionNode", "Initialized $id")
    }

    override fun process(inputs: Map<String, GLTexture>): GLTexture {
        check(isInitialized) { "Node not initialized" }
        val input = inputs["input"] ?: throw IllegalArgumentException("ColorCorrectionNode $id requires input")

        if (framebuffer == null || framebuffer!!.width != input.width || framebuffer!!.height != input.height) {
            framebuffer?.close()
            framebuffer = GLFramebuffer.create(input.width, input.height)
        }

        framebuffer!!.bind()
        GLES20.glUseProgram(programId)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(input.target, input.textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "sTexture"), 0)

        val lift = parameters.getVec3("lift", listOf(0f,0f,0f))
        val gamma = parameters.getVec3("gamma", listOf(1f,1f,1f))
        val gain = parameters.getVec3("gain", listOf(1f,1f,1f))

        GLES20.glUniform3f(GLES20.glGetUniformLocation(programId, "uLift"), lift[0], lift[1], lift[2])
        GLES20.glUniform3f(GLES20.glGetUniformLocation(programId, "uGamma"), gamma[0], gamma[1], gamma[2])
        GLES20.glUniform3f(GLES20.glGetUniformLocation(programId, "uGain"), gain[0], gain[1], gain[2])

        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, "uSaturation"), parameters.getFloat("saturation", 1f))
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, "uHueShift"), parameters.getFloat("hueShift", 0f))
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, "uBrightness"), parameters.getFloat("brightness", 0f))
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, "uContrast"), parameters.getFloat("contrast", 1f))
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, "uTemperature"), parameters.getFloat("temperature", 0f))

        drawQuad()
        framebuffer!!.unbind()

        return GLTexture.wrapExisting(framebuffer!!.texture.textureId, framebuffer!!.texture.target, framebuffer!!.width, framebuffer!!.height, owned = false)
    }

    override fun onRelease() {
        framebuffer?.close()
        framebuffer = null
    }

    override fun setInputConnection(inputSocketId: String, fromNodeId: String?, fromOutputId: String?) {}

    companion object {
        private const val FRAGMENT_SHADER_CC = "precision mediump float;\n" +
"            varying vec2 vTexCoord;\n" +
"            \n" +
"            uniform sampler2D sTexture;\n" +
"            uniform vec3 uLift;\n" +
"            uniform vec3 uGamma;\n" +
"            uniform vec3 uGain;\n" +
"            uniform float uSaturation;\n" +
"            uniform float uHueShift;\n" +
"            uniform float uBrightness;\n" +
"            uniform float uContrast;\n" +
"            uniform float uTemperature;\n" +
"\n" +
"            vec3 rgbToHsl(vec3 c) {\n" +
"                float maxC = max(max(c.r, c.g), c.b);\n" +
"                float minC = min(min(c.r, c.g), c.b);\n" +
"                float l = (maxC + minC) * 0.5;\n" +
"                float s = 0.0; float h = 0.0;\n" +
"                if (maxC != minC) {\n" +
"                    float d = maxC - minC;\n" +
"                    s = l > 0.5 ? d / (2.0 - maxC - minC) : d / (maxC + minC);\n" +
"                    if (maxC == c.r) h = (c.g - c.b) / d + (c.g < c.b ? 6.0 : 0.0);\n" +
"                    else if (maxC == c.g) h = (c.b - c.r) / d + 2.0;\n" +
"                    else h = (c.r - c.g) / d + 4.0;\n" +
"                    h /= 6.0;\n" +
"                }\n" +
"                return vec3(h, s, l);\n" +
"            }\n" +
"            float hueToRgb(float p, float q, float t) {\n" +
"                if (t < 0.0) t += 1.0;\n" +
"                if (t > 1.0) t -= 1.0;\n" +
"                if (t < 1.0/6.0) return p + (q - p) * 6.0 * t;\n" +
"                if (t < 1.0/2.0) return q;\n" +
"                if (t < 2.0/3.0) return p + (q - p) * (2.0/3.0 - t) * 6.0;\n" +
"                return p;\n" +
"            }\n" +
"            vec3 hslToRgb(vec3 hsl) {\n" +
"                float h = hsl.x; float s = hsl.y; float l = hsl.z;\n" +
"                float r; float g; float b;\n" +
"                if (s == 0.0) { r = l; g = l; b = l; }\n" +
"                else {\n" +
"                    float q = l < 0.5 ? l * (1.0 + s) : l + s - l * s;\n" +
"                    float p = 2.0 * l - q;\n" +
"                    r = hueToRgb(p, q, h + 1.0/3.0);\n" +
"                    g = hueToRgb(p, q, h);\n" +
"                    b = hueToRgb(p, q, h - 1.0/3.0);\n" +
"                }\n" +
"                return vec3(r,g,b);\n" +
"            }\n" +
"\n" +
"            void main() {\n" +
"                vec4 src = texture2D(sTexture, vTexCoord);\n" +
"                vec3 color = src.rgb;\n" +
"\n" +
"                // Lift/Gamma/Gain – ASC CDL approximation\n" +
"                // lift: add to shadows, gain: multiply highlights, gamma: power for mids\n" +
"                // Apply: (color + lift) * gain ^ (1/gamma) – simplified\n" +
"                color = color + uLift;\n" +
"                color = max(color, vec3(0.0));\n" +
"                // Avoid div by zero in gamma\n" +
"                vec3 safeGamma = max(uGamma, vec3(0.1));\n" +
"                color = pow(color, vec3(1.0) / safeGamma);\n" +
"                color = color * uGain;\n" +
"\n" +
"                // Brightness / Contrast\n" +
"                color = (color - 0.5) * uContrast + 0.5 + uBrightness;\n" +
"\n" +
"                // Temperature – warm (more red) vs cool (more blue)\n" +
"                if (uTemperature > 0.0) {\n" +
"                    color.r += uTemperature * 0.2;\n" +
"                    color.b -= uTemperature * 0.1;\n" +
"                } else {\n" +
"                    color.b += abs(uTemperature) * 0.2;\n" +
"                    color.r -= abs(uTemperature) * 0.1;\n" +
"                }\n" +
"\n" +
"                // HSL adjustments\n" +
"                vec3 hsl = rgbToHsl(clamp(color, 0.0, 1.0));\n" +
"                hsl.x = fract(hsl.x + uHueShift); // hue shift wraps\n" +
"                hsl.y = clamp(hsl.y * uSaturation, 0.0, 1.0);\n" +
"                color = hslToRgb(hsl);\n" +
"\n" +
"                gl_FragColor = vec4(clamp(color, 0.0, 1.0), src.a);\n" +
"            }\n"
    }
}

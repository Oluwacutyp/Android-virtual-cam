package com.androidvirtualcam.compositor.nodes

import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import com.androidvirtualcam.compositor.*

/**
 * BlendNode – 15 blend modes: Normal Multiply Screen Overlay SoftLight HardLight
 * Difference Exclusion Hue Saturation Color Luminosity Add Subtract Divide.
 *
 * Inputs:
 * - base: bottom layer
 * - blend: top layer
 *
 * Parameters:
 * - mode: string blend mode
 * - opacity: float 0..1
 */
class BlendNode(
    override val id: String,
    override var parameters: NodeParameters = NodeParameters.builder()
        .string("mode", "Normal")
        .float("opacity", 1f)
        .build()
) : BaseCompositorNode(
    id = id,
    type = "Blend",
    inputSockets = listOf(
        InputSocket("base", "base", SocketType.TEXTURE, displayName = "Base"),
        InputSocket("blend", "blend", SocketType.TEXTURE, displayName = "Blend")
    ),
    outputSocket = OutputSocket("output", "output", SocketType.TEXTURE),
    parameters = parameters
), CompositorGraph.MutableConnectionsNode {

    private var framebuffer: GLFramebuffer? = null

    enum class BlendMode(val shaderValue: Int) {
        Normal(0), Multiply(1), Screen(2), Overlay(3), SoftLight(4), HardLight(5),
        Difference(6), Exclusion(7), Hue(8), Saturation(9), Color(10), Luminosity(11),
        Add(12), Subtract(13), Divide(14)
    }

    override fun onInitialize() {
        try {
            compileProgram(VERTEX_SHADER_SIMPLE, FRAGMENT_SHADER_BLEND)
        } catch (e: Exception) {
            Log.e("BlendNode", "Shader compile failed, fallback to passthrough", e)
            try { compileProgram(PASSTHROUGH_VERT, PASSTHROUGH_FRAG) } catch (_: Exception) {}
        }
        Log.d("BlendNode", "Initialized $id")
    }

    override fun process(inputs: Map<String, GLTexture>): GLTexture {
        check(isInitialized) { "Node not initialized" }
        val base = inputs["base"] ?: throw IllegalArgumentException("BlendNode $id requires base")
        val blend = inputs["blend"] ?: throw IllegalArgumentException("BlendNode $id requires blend")

        val w = base.width
        val h = base.height

        if (framebuffer == null || framebuffer!!.width != w || framebuffer!!.height != h) {
            framebuffer?.close()
            framebuffer = GLFramebuffer.create(w, h)
        }

        val modeStr = parameters.getString("mode", "Normal")
        val mode = try { BlendMode.valueOf(modeStr) } catch (_: Exception) { BlendMode.Normal }
        val opacity = parameters.getFloat("opacity", 1f)

        framebuffer!!.bind()
        GLES20.glUseProgram(programId)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(base.target, base.textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "sBase"), 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(blend.target, blend.textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "sBlend"), 1)

        GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "uMode"), mode.shaderValue)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, "uOpacity"), opacity)

        drawQuad()
        framebuffer!!.unbind()

        return GLTexture.wrapExisting(framebuffer!!.texture.textureId, framebuffer!!.texture.target, w, h, owned = false)
    }

    override fun onRelease() {
        framebuffer?.close()
        framebuffer = null
    }

    override fun setInputConnection(inputSocketId: String, fromNodeId: String?, fromOutputId: String?) {}

    companion object {
        private const val FRAGMENT_SHADER_BLEND = "precision mediump float;\n" +
"            varying vec2 vTexCoord;\n" +
"            \n" +
"            uniform sampler2D sBase;\n" +
"            uniform sampler2D sBlend;\n" +
"            uniform int uMode;\n" +
"            uniform float uOpacity;\n" +
"\n" +
"            // Luminance\n" +
"            float lum(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }\n" +
"\n" +
"            // RGB to HSL helpers\n" +
"            vec3 rgbToHsl(vec3 c) {\n" +
"                float maxC = max(max(c.r, c.g), c.b);\n" +
"                float minC = min(min(c.r, c.g), c.b);\n" +
"                float l = (maxC + minC) * 0.5;\n" +
"                float s = 0.0;\n" +
"                float h = 0.0;\n" +
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
"\n" +
"            float hueToRgb(float p, float q, float t) {\n" +
"                if (t < 0.0) t += 1.0;\n" +
"                if (t > 1.0) t -= 1.0;\n" +
"                if (t < 1.0/6.0) return p + (q - p) * 6.0 * t;\n" +
"                if (t < 1.0/2.0) return q;\n" +
"                if (t < 2.0/3.0) return p + (q - p) * (2.0/3.0 - t) * 6.0;\n" +
"                return p;\n" +
"            }\n" +
"\n" +
"            vec3 hslToRgb(vec3 hsl) {\n" +
"                float h = hsl.x; float s = hsl.y; float l = hsl.z;\n" +
"                float r; float g; float b;\n" +
"                if (s == 0.0) {\n" +
"                    r = l; g = l; b = l;\n" +
"                } else {\n" +
"                    float q = l < 0.5 ? l * (1.0 + s) : l + s - l * s;\n" +
"                    float p = 2.0 * l - q;\n" +
"                    r = hueToRgb(p, q, h + 1.0/3.0);\n" +
"                    g = hueToRgb(p, q, h);\n" +
"                    b = hueToRgb(p, q, h - 1.0/3.0);\n" +
"                }\n" +
"                return vec3(r, g, b);\n" +
"            }\n" +
"\n" +
"            vec3 blendHue(vec3 base, vec3 blend) {\n" +
"                vec3 baseHsl = rgbToHsl(base);\n" +
"                vec3 blendHsl = rgbToHsl(blend);\n" +
"                return hslToRgb(vec3(blendHsl.x, baseHsl.y, baseHsl.z));\n" +
"            }\n" +
"            vec3 blendSaturation(vec3 base, vec3 blend) {\n" +
"                vec3 baseHsl = rgbToHsl(base);\n" +
"                vec3 blendHsl = rgbToHsl(blend);\n" +
"                return hslToRgb(vec3(baseHsl.x, blendHsl.y, baseHsl.z));\n" +
"            }\n" +
"            vec3 blendColor(vec3 base, vec3 blend) {\n" +
"                vec3 baseHsl = rgbToHsl(base);\n" +
"                vec3 blendHsl = rgbToHsl(blend);\n" +
"                return hslToRgb(vec3(blendHsl.x, blendHsl.y, blendHsl.z));\n" +
"            }\n" +
"            vec3 blendLuminosity(vec3 base, vec3 blend) {\n" +
"                vec3 baseHsl = rgbToHsl(base);\n" +
"                vec3 blendHsl = rgbToHsl(blend);\n" +
"                return hslToRgb(vec3(baseHsl.x, baseHsl.y, blendHsl.z));\n" +
"            }\n" +
"\n" +
"            void main() {\n" +
"                vec4 base = texture2D(sBase, vTexCoord);\n" +
"                vec4 blend = texture2D(sBlend, vTexCoord);\n" +
"                vec3 result;\n" +
"\n" +
"                if (uMode == 0) { // Normal\n" +
"                    result = blend.rgb;\n" +
"                } else if (uMode == 1) { // Multiply\n" +
"                    result = base.rgb * blend.rgb;\n" +
"                } else if (uMode == 2) { // Screen\n" +
"                    result = 1.0 - (1.0 - base.rgb) * (1.0 - blend.rgb);\n" +
"                } else if (uMode == 3) { // Overlay\n" +
"                    result = mix(\n" +
"                        2.0 * base.rgb * blend.rgb,\n" +
"                        1.0 - 2.0 * (1.0 - base.rgb) * (1.0 - blend.rgb),\n" +
"                        step(0.5, base.rgb)\n" +
"                    );\n" +
"                } else if (uMode == 4) { // SoftLight\n" +
"                    result = mix(\n" +
"                        base.rgb - (1.0 - 2.0 * blend.rgb) * base.rgb * (1.0 - base.rgb),\n" +
"                        base.rgb + (2.0 * blend.rgb - 1.0) * (sqrt(base.rgb) - base.rgb),\n" +
"                        step(0.5, blend.rgb)\n" +
"                    );\n" +
"                } else if (uMode == 5) { // HardLight\n" +
"                    result = mix(\n" +
"                        2.0 * base.rgb * blend.rgb,\n" +
"                        1.0 - 2.0 * (1.0 - base.rgb) * (1.0 - blend.rgb),\n" +
"                        step(0.5, blend.rgb)\n" +
"                    );\n" +
"                } else if (uMode == 6) { // Difference\n" +
"                    result = abs(base.rgb - blend.rgb);\n" +
"                } else if (uMode == 7) { // Exclusion\n" +
"                    result = base.rgb + blend.rgb - 2.0 * base.rgb * blend.rgb;\n" +
"                } else if (uMode == 8) { // Hue\n" +
"                    result = blendHue(base.rgb, blend.rgb);\n" +
"                } else if (uMode == 9) { // Saturation\n" +
"                    result = blendSaturation(base.rgb, blend.rgb);\n" +
"                } else if (uMode == 10) { // Color\n" +
"                    result = blendColor(base.rgb, blend.rgb);\n" +
"                } else if (uMode == 11) { // Luminosity\n" +
"                    result = blendLuminosity(base.rgb, blend.rgb);\n" +
"                } else if (uMode == 12) { // Add\n" +
"                    result = min(base.rgb + blend.rgb, vec3(1.0));\n" +
"                } else if (uMode == 13) { // Subtract\n" +
"                    result = max(base.rgb - blend.rgb, vec3(0.0));\n" +
"                } else if (uMode == 14) { // Divide\n" +
"                    result = base.rgb / max(blend.rgb, vec3(0.001));\n" +
"                    result = min(result, vec3(1.0));\n" +
"                } else {\n" +
"                    result = blend.rgb;\n" +
"                }\n" +
"\n" +
"                float alpha = blend.a * uOpacity + base.a * (1.0 - blend.a * uOpacity);\n" +
"                vec3 finalRgb = mix(base.rgb, result, blend.a * uOpacity);\n" +
"\n" +
"                gl_FragColor = vec4(finalRgb, alpha);\n" +
"            }\n"
    }
}

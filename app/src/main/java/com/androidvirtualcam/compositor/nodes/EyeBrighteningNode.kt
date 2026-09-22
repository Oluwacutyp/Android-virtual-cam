package com.androidvirtualcam.compositor.nodes

import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import com.androidvirtualcam.compositor.*
import com.androidvirtualcam.face.FaceLandmarkManager

/**
 * EyeBrighteningNode – beauty filter that brightens eyes
 * 
 * Uses face landmarks – leftEye, rightEye positions and contours
 * Brightens eye whites, enhances iris, adds catchlight
 * 
 * Parameters:
 * - brightening: float 0..1
 * - catchlight: bool (add sparkle)
 * - eyeSize: float (enlarge eyes slightly)
 */
class EyeBrighteningNode(
    override val id: String,
    override var parameters: NodeParameters = NodeParameters.builder()
        .float("brightening", 0.6f)
        .float("eyeSize", 0.1f)
        .bool("catchlight", true)
        .float("saturation", 0.2f)
        .build()
) : BaseCompositorNode(
    id = id,
    type = "EyeBrightening",
    inputSockets = listOf(
        InputSocket("input", "input", SocketType.TEXTURE, displayName = "Input")
    ),
    outputSocket = OutputSocket("output", "output", SocketType.TEXTURE),
    parameters = parameters
), CompositorGraph.MutableConnectionsNode {

    private var framebuffer: GLFramebuffer? = null

    override fun onInitialize() {
        try {
            compileProgram(VERTEX_SHADER_SIMPLE, FRAGMENT_SHADER_EYE)
        } catch (e: Exception) {
            Log.e("EyeBrighteningNode", "Shader compile failed, fallback to passthrough", e)
            try { compileProgram(PASSTHROUGH_VERT, PASSTHROUGH_FRAG) } catch (_: Exception) {}
        }
        Log.d("EyeBrighteningNode", "Initialized $id")
    }

    override fun process(inputs: Map<String, GLTexture>): GLTexture {
        check(isInitialized) { "Node not initialized" }
        val input = inputs["input"] ?: throw IllegalArgumentException("EyeBrighteningNode $id requires input")

        if (framebuffer == null || framebuffer!!.width != input.width || framebuffer!!.height != input.height) {
            framebuffer?.close()
            framebuffer = GLFramebuffer.create(input.width, input.height)
        }

        framebuffer!!.bind()
        GLES20.glUseProgram(programId)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(input.target, input.textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "sTexture"), 0)

        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, "uBrightening"), parameters.getFloat("brightening", 0.6f))
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, "uEyeSize"), parameters.getFloat("eyeSize", 0.1f))
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "uCatchlight"), if (parameters.getBool("catchlight", true)) 1 else 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, "uSaturation"), parameters.getFloat("saturation", 0.2f))
        GLES20.glUniform2f(GLES20.glGetUniformLocation(programId, "uTexelSize"), 1f / input.width, 1f / input.height)

        val faces = FaceLandmarkManager.getFaces()
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "uFaceCount"), faces.size)
        for (i in 0 until minOf(faces.size, 3)) {
            val face = faces[i]
            val prefix = "uFaces[$i]."
            GLES20.glUniform2f(GLES20.glGetUniformLocation(programId, prefix + "leftEye"), face.leftEye?.x ?: 0f, face.leftEye?.y ?: 0f)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(programId, prefix + "rightEye"), face.rightEye?.x ?: 0f, face.rightEye?.y ?: 0f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, prefix + "eyeDist"), face.eyeDistance)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, prefix + "leftOpen"), face.leftEyeOpenProb)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, prefix + "rightOpen"), face.rightEyeOpenProb)
        }

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
        private const val FRAGMENT_SHADER_EYE = "precision mediump float;\n" +
"            varying vec2 vTexCoord;\n" +
"            \n" +
"            uniform sampler2D sTexture;\n" +
"            uniform float uBrightening;\n" +
"            uniform float uEyeSize;\n" +
"            uniform int uCatchlight;\n" +
"            uniform float uSaturation;\n" +
"            uniform int uFaceCount;\n" +
"            uniform vec2 uTexelSize;\n" +
"\n" +
"            struct Face {\n" +
"                vec2 leftEye;\n" +
"                vec2 rightEye;\n" +
"                float eyeDist;\n" +
"                float leftOpen;\n" +
"                float rightOpen;\n" +
"            };\n" +
"            uniform Face uFaces[3];\n" +
"\n" +
"            // Eye brightening function\n" +
"            vec3 brightenEye(vec3 color, float eyeMask, float brightening, float saturation) {\n" +
"                // Increase brightness of eye whites, enhance contrast\n" +
"                float luma = dot(color, vec3(0.299, 0.587, 0.114));\n" +
"                vec3 brightened = color + vec3(brightening * 0.3 * eyeMask);\n" +
"                // Increase saturation slightly for iris\n" +
"                vec3 gray = vec3(luma);\n" +
"                vec3 saturated = mix(gray, color, 1.0 + saturation * eyeMask);\n" +
"                vec3 result = mix(saturated, brightened, 0.5);\n" +
"                // Add subtle blue tint to whites\n" +
"                result.b += eyeMask * 0.05 * brightening;\n" +
"                return result;\n" +
"            }\n" +
"\n" +
"            float eyeMask(vec2 uv, vec2 eyePos, float eyeDist, float eyeOpen) {\n" +
"                if (eyePos.x == 0.0 && eyePos.y == 0.0) return 0.0;\n" +
"                if (eyeOpen < 0.3) return 0.0; // eye closed\n" +
"                float dist = distance(uv, eyePos);\n" +
"                float radius = eyeDist * 0.25; // eye radius ~25% of eye distance\n" +
"                // Smooth circle\n" +
"                float mask = 1.0 - smoothstep(radius * 0.8, radius * 1.5, dist);\n" +
"                // Ellipse – eyes are wider than tall\n" +
"                vec2 delta = uv - eyePos;\n" +
"                float ellipse = (delta.x * delta.x) / (radius * radius * 1.5) + (delta.y * delta.y) / (radius * radius * 0.7);\n" +
"                float ellipseMask = 1.0 - smoothstep(0.8, 1.2, ellipse);\n" +
"                return max(mask, ellipseMask) * eyeOpen;\n" +
"            }\n" +
"\n" +
"            void main() {\n" +
"                vec4 src = texture2D(sTexture, vTexCoord);\n" +
"                vec3 color = src.rgb;\n" +
"                vec2 uv = vTexCoord;\n" +
"\n" +
"                float totalEyeMask = 0.0;\n" +
"                vec2 closestEye = vec2(0.0);\n" +
"                float closestDist = 10.0;\n" +
"\n" +
"                for (int i=0; i<3; i++) {\n" +
"                    if (i >= uFaceCount) break;\n" +
"                    Face face = uFaces[i];\n" +
"                    float leftMask = eyeMask(uv, face.leftEye, face.eyeDist, face.leftOpen);\n" +
"                    float rightMask = eyeMask(uv, face.rightEye, face.eyeDist, face.rightOpen);\n" +
"                    float faceMask = max(leftMask, rightMask);\n" +
"                    totalEyeMask = max(totalEyeMask, faceMask);\n" +
"\n" +
"                    // Find closest eye for catchlight and enlargement\n" +
"                    float dl = distance(uv, face.leftEye);\n" +
"                    float dr = distance(uv, face.rightEye);\n" +
"                    if (dl < closestDist) { closestDist = dl; closestEye = face.leftEye; }\n" +
"                    if (dr < closestDist) { closestDist = dr; closestEye = face.rightEye; }\n" +
"                }\n" +
"\n" +
"                if (totalEyeMask > 0.01) {\n" +
"                    // Brighten\n" +
"                    color = brightenEye(color, totalEyeMask, uBrightening, uSaturation);\n" +
"\n" +
"                    // Catchlight – small white dot in eye\n" +
"                    if (uCatchlight == 1) {\n" +
"                        float catchlightDist = distance(uv, closestEye + vec2(0.005, -0.005));\n" +
"                        float catchlight = 1.0 - smoothstep(0.0, 0.008, catchlightDist);\n" +
"                        catchlight *= totalEyeMask;\n" +
"                        color += vec3(catchlight * 0.8);\n" +
"                    }\n" +
"\n" +
"                    // Eye enlargement – slight warp (simple: sample slightly offset towards eye center)\n" +
"                    if (uEyeSize > 0.01) {\n" +
"                        vec2 dir = uv - closestEye;\n" +
"                        float dist = length(dir);\n" +
"                        float radius = 0.03; // affect area\n" +
"                        if (dist < radius) {\n" +
"                            float factor = (1.0 - dist / radius) * uEyeSize * 0.2;\n" +
"                            vec2 newUv = uv - normalize(dir) * factor;\n" +
"                            vec3 enlarged = texture2D(sTexture, newUv).rgb;\n" +
"                            color = mix(color, enlarged, totalEyeMask * 0.5);\n" +
"                        }\n" +
"                    }\n" +
"                }\n" +
"\n" +
"                gl_FragColor = vec4(clamp(color, 0.0, 1.0), src.a);\n" +
"            }\n"
    }
}

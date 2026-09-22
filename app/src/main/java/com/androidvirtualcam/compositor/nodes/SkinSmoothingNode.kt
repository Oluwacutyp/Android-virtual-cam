package com.androidvirtualcam.compositor.nodes

import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import com.androidvirtualcam.compositor.*
import com.androidvirtualcam.face.FaceLandmarkManager

/**
 * SkinSmoothingNode – beauty filter, bilateral-like smoothing using face landmarks
 * 
 * Uses ML Kit face detection to get face bounding boxes, then applies skin smoothing only on skin regions.
 * 
 * Algorithm:
 * - 9-tap box blur for base smoothing
 * - Skin detection in YCrCb: Cr 135-180, Cb 85-135
 * - Edge preservation: if luminance difference > threshold, reduce smoothing
 * - Face mask: only smooth inside face bounding boxes (or full frame if no face)
 * - Strength 0..1 controls mix
 * 
 * Parameters:
 * - smoothing: float 0..1 (0 = off, 1 = max)
 * - skinMask: bool (use skin color detection)
 * - faceOnly: bool (only smooth face region)
 * - textureSize: int for blur radius
 */
class SkinSmoothingNode(
    override val id: String,
    override var parameters: NodeParameters = NodeParameters.builder()
        .float("smoothing", 0.6f)
        .float("blurRadius", 1.5f)
        .bool("skinMask", true)
        .bool("faceOnly", true)
        .float("edgeThreshold", 0.2f)
        .build()
) : BaseCompositorNode(
    id = id,
    type = "SkinSmoothing",
    inputSockets = listOf(
        InputSocket("input", "input", SocketType.TEXTURE, displayName = "Input")
    ),
    outputSocket = OutputSocket("output", "output", SocketType.TEXTURE),
    parameters = parameters
), CompositorGraph.MutableConnectionsNode {

    private var framebuffer: GLFramebuffer? = null

    override fun onInitialize() {
        try {
            compileProgram(VERTEX_SHADER_SIMPLE, FRAGMENT_SHADER_SKIN_SMOOTHING)
        } catch (e: Exception) {
            Log.e("SkinSmoothingNode", "Shader compile failed, fallback to passthrough", e)
            try { compileProgram(PASSTHROUGH_VERT, PASSTHROUGH_FRAG) } catch (_: Exception) {}
        }
        Log.d("SkinSmoothingNode", "Initialized $id")
    }

    override fun process(inputs: Map<String, GLTexture>): GLTexture {
        check(isInitialized) { "Node not initialized" }
        val input = inputs["input"] ?: throw IllegalArgumentException("SkinSmoothingNode $id requires input")

        if (framebuffer == null || framebuffer!!.width != input.width || framebuffer!!.height != input.height) {
            framebuffer?.close()
            framebuffer = GLFramebuffer.create(input.width, input.height)
        }

        framebuffer!!.bind()
        GLES20.glUseProgram(programId)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(input.target, input.textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "sTexture"), 0)

        val smoothing = parameters.getFloat("smoothing", 0.6f)
        val blurRadius = parameters.getFloat("blurRadius", 1.5f)
        val useSkinMask = parameters.getBool("skinMask", true)
        val faceOnly = parameters.getBool("faceOnly", true)
        val edgeThresh = parameters.getFloat("edgeThreshold", 0.2f)

        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, "uSmoothing"), smoothing)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, "uBlurRadius"), blurRadius)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, "uEdgeThreshold"), edgeThresh)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "uUseSkinMask"), if (useSkinMask) 1 else 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "uFaceOnly"), if (faceOnly) 1 else 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(programId, "uTexelSize"), 1f / input.width, 1f / input.height)

        // Face uniforms – up to 3 faces
        val faces = FaceLandmarkManager.getFaces()
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "uFaceCount"), faces.size)
        for (i in 0 until minOf(faces.size, 3)) {
            val face = faces[i]
            val prefix = "uFaces[$i]."
            GLES20.glUniform4f(GLES20.glGetUniformLocation(programId, prefix + "rect"), face.boundingBox.left, face.boundingBox.top, face.boundingBox.width(), face.boundingBox.height())
            GLES20.glUniform2f(GLES20.glGetUniformLocation(programId, prefix + "leftEye"), face.leftEye?.x ?: 0f, face.leftEye?.y ?: 0f)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(programId, prefix + "rightEye"), face.rightEye?.x ?: 0f, face.rightEye?.y ?: 0f)
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
        private const val FRAGMENT_SHADER_SKIN_SMOOTHING = "precision mediump float;\n" +
"            varying vec2 vTexCoord;\n" +
"            \n" +
"            uniform sampler2D sTexture;\n" +
"            uniform float uSmoothing;\n" +
"            uniform float uBlurRadius;\n" +
"            uniform float uEdgeThreshold;\n" +
"            uniform int uUseSkinMask;\n" +
"            uniform int uFaceOnly;\n" +
"            uniform int uFaceCount;\n" +
"            uniform vec2 uTexelSize;\n" +
"\n" +
"            struct Face {\n" +
"                vec4 rect; // x,y,w,h\n" +
"                vec2 leftEye;\n" +
"                vec2 rightEye;\n" +
"            };\n" +
"            uniform Face uFaces[3];\n" +
"\n" +
"            // Skin detection in YCrCb\n" +
"            bool isSkin(vec3 rgb) {\n" +
"                // Convert to YCrCb\n" +
"                float r = rgb.r;\n" +
"                float g = rgb.g;\n" +
"                float b = rgb.b;\n" +
"                float Y = 0.299*r + 0.587*g + 0.114*b;\n" +
"                float Cr = r - Y;\n" +
"                float Cb = b - Y;\n" +
"                // Simplified skin range in RGB normalized\n" +
"                // Skin: R>95 G>40 B>20, R>G, R>B, |R-G|>15, Cr 0.05-0.15, Cb -0.05-0.05 approx\n" +
"                bool cond1 = r > 0.3725 && g > 0.1568 && b > 0.0784; // 95,40,20 /255\n" +
"                bool cond2 = r > g && r > b;\n" +
"                bool cond3 = abs(r - g) > 0.0588; // 15/255\n" +
"                // CrCb check approximated\n" +
"                float cr = 0.5 + 0.5 * (Cr);\n" +
"                float cb = 0.5 + 0.5 * (Cb);\n" +
"                bool cond4 = cr > 0.53 && cr < 0.71 && cb > 0.33 && cb < 0.53;\n" +
"                return cond1 && cond2 && cond3 && cond4;\n" +
"            }\n" +
"\n" +
"            bool insideFace(vec2 uv) {\n" +
"                if (uFaceCount == 0) return true; // if no face, treat as face for full-frame smoothing when faceOnly=false handled elsewhere\n" +
"                for (int i=0; i<3; i++) {\n" +
"                    if (i >= uFaceCount) break;\n" +
"                    vec4 r = uFaces[i].rect;\n" +
"                    if (uv.x >= r.x && uv.x <= r.x + r.z && uv.y >= r.y && uv.y <= r.y + r.w) {\n" +
"                        return true;\n" +
"                    }\n" +
"                }\n" +
"                return false;\n" +
"            }\n" +
"\n" +
"            vec3 blur9(sampler2D tex, vec2 uv, vec2 texel, float radius) {\n" +
"                vec3 sum = vec3(0.0);\n" +
"                sum += texture2D(tex, uv + vec2(-texel.x, -texel.y) * radius).rgb * 0.0625;\n" +
"                sum += texture2D(tex, uv + vec2(0.0, -texel.y) * radius).rgb * 0.125;\n" +
"                sum += texture2D(tex, uv + vec2(texel.x, -texel.y) * radius).rgb * 0.0625;\n" +
"                sum += texture2D(tex, uv + vec2(-texel.x, 0.0) * radius).rgb * 0.125;\n" +
"                sum += texture2D(tex, uv).rgb * 0.25;\n" +
"                sum += texture2D(tex, uv + vec2(texel.x, 0.0) * radius).rgb * 0.125;\n" +
"                sum += texture2D(tex, uv + vec2(-texel.x, texel.y) * radius).rgb * 0.0625;\n" +
"                sum += texture2D(tex, uv + vec2(0.0, texel.y) * radius).rgb * 0.125;\n" +
"                sum += texture2D(tex, uv + vec2(texel.x, texel.y) * radius).rgb * 0.0625;\n" +
"                return sum;\n" +
"            }\n" +
"\n" +
"            void main() {\n" +
"                vec4 src = texture2D(sTexture, vTexCoord);\n" +
"                vec3 original = src.rgb;\n" +
"\n" +
"                // Face-only check\n" +
"                bool inFace = insideFace(vTexCoord);\n" +
"                if (uFaceOnly == 1 && !inFace) {\n" +
"                    gl_FragColor = src;\n" +
"                    return;\n" +
"                }\n" +
"\n" +
"                vec3 blurred = blur9(sTexture, vTexCoord, uTexelSize, uBlurRadius);\n" +
"\n" +
"                // Edge preservation – if luma difference large, reduce blur\n" +
"                float lumaOrig = dot(original, vec3(0.299, 0.587, 0.114));\n" +
"                float lumaBlur = dot(blurred, vec3(0.299, 0.587, 0.114));\n" +
"                float edge = abs(lumaOrig - lumaBlur);\n" +
"                float edgeFactor = 1.0 - smoothstep(0.0, uEdgeThreshold, edge); // 1 when edge small, 0 when edge large\n" +
"\n" +
"                // Skin mask\n" +
"                float skinMask = 1.0;\n" +
"                if (uUseSkinMask == 1) {\n" +
"                    skinMask = isSkin(original) ? 1.0 : 0.3; // still some smoothing even non-skin but less\n" +
"                }\n" +
"\n" +
"                float blend = uSmoothing * edgeFactor * skinMask;\n" +
"\n" +
"                // If faceOnly, fade at face borders\n" +
"                if (uFaceOnly == 1 && uFaceCount > 0) {\n" +
"                    // simple feather – we already check inside, but we can reduce near border\n" +
"                    // for now keep blend as is\n" +
"                }\n" +
"\n" +
"                vec3 result = mix(original, blurred, blend);\n" +
"\n" +
"                // Slight brightening for skin smoothing glow\n" +
"                result = mix(result, result * 1.05, blend * 0.2);\n" +
"\n" +
"                gl_FragColor = vec4(clamp(result, 0.0, 1.0), src.a);\n" +
"            }\n"
    }
}

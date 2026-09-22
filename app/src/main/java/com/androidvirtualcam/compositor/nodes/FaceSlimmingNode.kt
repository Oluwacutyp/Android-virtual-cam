package com.androidvirtualcam.compositor.nodes

import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import com.androidvirtualcam.compositor.*
import com.androidvirtualcam.face.FaceLandmarkManager

/**
 * FaceSlimmingNode – beauty filter that slims face by warping
 * 
 * Uses face landmarks from ML Kit – face oval contour and bounding box
 * Warps pixels inside face to make face appear slimmer (V-shape)
 * 
 * Algorithm:
 * - For each face, get center and width
 * - For pixels inside face, compute horizontal distance from center
 * - Apply inward displacement: delta = (distance / radius) * strength * (1 - verticalFactor)
 * - Vertical factor reduces effect near forehead/chin
 * - Strength 0..1
 * 
 * Parameters:
 * - slimming: float 0..1
 * - vShape: float 0..1 (how much V shape – more chin narrowing)
 */
class FaceSlimmingNode(
    override val id: String,
    override var parameters: NodeParameters = NodeParameters.builder()
        .float("slimming", 0.5f)
        .float("vShape", 0.3f)
        .bool("faceOnly", true)
        .build()
) : BaseCompositorNode(
    id = id,
    type = "FaceSlimming",
    inputSockets = listOf(
        InputSocket("input", "input", SocketType.TEXTURE, displayName = "Input")
    ),
    outputSocket = OutputSocket("output", "output", SocketType.TEXTURE),
    parameters = parameters
), CompositorGraph.MutableConnectionsNode {

    private var framebuffer: GLFramebuffer? = null

    override fun onInitialize() {
        try {
            compileProgram(VERTEX_SHADER_SIMPLE, FRAGMENT_SHADER_SLIMMING)
        } catch (e: Exception) {
            Log.e("FaceSlimmingNode", "Shader compile failed, fallback to passthrough", e)
            try { compileProgram(PASSTHROUGH_VERT, PASSTHROUGH_FRAG) } catch (_: Exception) {}
        }
        Log.d("FaceSlimmingNode", "Initialized $id")
    }

    override fun process(inputs: Map<String, GLTexture>): GLTexture {
        check(isInitialized) { "Node not initialized" }
        val input = inputs["input"] ?: throw IllegalArgumentException("FaceSlimmingNode $id requires input")

        if (framebuffer == null || framebuffer!!.width != input.width || framebuffer!!.height != input.height) {
            framebuffer?.close()
            framebuffer = GLFramebuffer.create(input.width, input.height)
        }

        framebuffer!!.bind()
        GLES20.glUseProgram(programId)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(input.target, input.textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "sTexture"), 0)

        val slimming = parameters.getFloat("slimming", 0.5f)
        val vShape = parameters.getFloat("vShape", 0.3f)

        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, "uSlimming"), slimming)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, "uVShape"), vShape)

        val faces = FaceLandmarkManager.getFaces()
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "uFaceCount"), faces.size)
        for (i in 0 until minOf(faces.size, 3)) {
            val face = faces[i]
            val prefix = "uFaces[$i]."
            GLES20.glUniform4f(GLES20.glGetUniformLocation(programId, prefix + "rect"), face.boundingBox.left, face.boundingBox.top, face.boundingBox.width(), face.boundingBox.height())
            GLES20.glUniform2f(GLES20.glGetUniformLocation(programId, prefix + "center"), face.center.x, face.center.y)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, prefix + "radius"), face.width * 0.5f)
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
        private const val FRAGMENT_SHADER_SLIMMING = "precision mediump float;\n" +
"            varying vec2 vTexCoord;\n" +
"            \n" +
"            uniform sampler2D sTexture;\n" +
"            uniform float uSlimming;\n" +
"            uniform float uVShape;\n" +
"            uniform int uFaceCount;\n" +
"\n" +
"            struct Face {\n" +
"                vec4 rect;\n" +
"                vec2 center;\n" +
"                float radius;\n" +
"                vec2 leftEye;\n" +
"                vec2 rightEye;\n" +
"            };\n" +
"            uniform Face uFaces[3];\n" +
"\n" +
"            // Warp function – returns new UV\n" +
"            vec2 warpFace(vec2 uv, Face face, float slimming, float vShape) {\n" +
"                vec2 center = face.center;\n" +
"                float radius = face.radius;\n" +
"                vec4 rect = face.rect;\n" +
"\n" +
"                // Check if inside face rect\n" +
"                if (uv.x < rect.x || uv.x > rect.x + rect.z || uv.y < rect.y || uv.y > rect.y + rect.w) {\n" +
"                    return uv;\n" +
"                }\n" +
"\n" +
"                // Distance from center\n" +
"                vec2 delta = uv - center;\n" +
"                float distX = abs(delta.x);\n" +
"                float distY = delta.y;\n" +
"\n" +
"                // Only affect horizontal – slimming pushes x towards center\n" +
"                // Vertical factor: more effect at lower face (chin) for V-shape\n" +
"                float verticalFactor = 0.0;\n" +
"                if (distY > 0.0) {\n" +
"                    // below center – chin area – more slimming if vShape\n" +
"                    verticalFactor = clamp(distY / (rect.w * 0.6), 0.0, 1.0);\n" +
"                    verticalFactor = mix(1.0, 1.0 + vShape, verticalFactor);\n" +
"                } else {\n" +
"                    // above center – less effect\n" +
"                    verticalFactor = 0.7;\n" +
"                }\n" +
"\n" +
"                // Normalized distance from center (0 at center, 1 at edge)\n" +
"                float normDist = distX / radius;\n" +
"                normDist = clamp(normDist, 0.0, 1.0);\n" +
"\n" +
"                // Slimming curve – stronger at edges\n" +
"                float slimFactor = pow(normDist, 1.5) * slimming * 0.15 * verticalFactor;\n" +
"\n" +
"                // Push towards center\n" +
"                float newX = uv.x;\n" +
"                if (delta.x > 0.0) {\n" +
"                    newX = uv.x - slimFactor;\n" +
"                } else {\n" +
"                    newX = uv.x + slimFactor;\n" +
"                }\n" +
"\n" +
"                return vec2(newX, uv.y);\n" +
"            }\n" +
"\n" +
"            void main() {\n" +
"                vec2 uv = vTexCoord;\n" +
"                // Apply warp for each face – chain\n" +
"                for (int i=0; i<3; i++) {\n" +
"                    if (i >= uFaceCount) break;\n" +
"                    uv = warpFace(uv, uFaces[i], uSlimming, uVShape);\n" +
"                }\n" +
"                // Clamp\n" +
"                uv = clamp(uv, 0.0, 1.0);\n" +
"                gl_FragColor = texture2D(sTexture, uv);\n" +
"            }\n"
    }
}

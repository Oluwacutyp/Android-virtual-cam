package com.androidvirtualcam.compositor.nodes

import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import com.androidvirtualcam.compositor.*
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * BlurNode – dual-pass Gaussian blur with sigma parameter, using two FBOs.
 *
 * Inputs:
 * - input: texture
 *
 * Parameters:
 * - sigma: float (0.1..20) blur radius sigma
 * - radius: int (1..25) kernel radius – auto from sigma if 0
 * - width/height: optional override
 *
 * Production: separable Gaussian, horizontal then vertical pass.
 */
class BlurNode(
    override val id: String,
    override var parameters: NodeParameters = NodeParameters.builder()
        .float("sigma", 4f)
        .int("radius", 0)
        .build()
) : BaseCompositorNode(
    id = id,
    type = "Blur",
    inputSockets = listOf(
        InputSocket("input", "input", SocketType.TEXTURE, displayName = "Input")
    ),
    outputSocket = OutputSocket("output", "output", SocketType.TEXTURE),
    parameters = parameters
), CompositorGraph.MutableConnectionsNode {

    private var intermediateFBO: GLFramebuffer? = null
    private var finalFBO: GLFramebuffer? = null
    private var horizontalProgram: Int = 0
    private var verticalProgram: Int = 0

    override fun onInitialize() {
        // Compile two programs – same shader source but we use same for both passes with direction uniform
        try {
            horizontalProgram = compileProgram(VERTEX_SHADER_SIMPLE, FRAGMENT_SHADER_GAUSSIAN)
        } catch (e: Exception) {
            Log.e("BlurNode", "Shader compile failed, fallback to passthrough", e)
            try { compileProgram(PASSTHROUGH_VERT, PASSTHROUGH_FRAG) } catch (_: Exception) {}
        }
        // Re-compile for vertical – we keep same program id logic but store second
        val vertProg = compileShaderProgram(VERTEX_SHADER_SIMPLE, FRAGMENT_SHADER_GAUSSIAN)
        verticalProgram = vertProg
        // Keep base programId as horizontal for base class cleanup? We'll manage both
        programId = horizontalProgram

        Log.d("BlurNode", "Initialized $id programs h=$horizontalProgram v=$verticalProgram")
    }

    private fun compileShaderProgram(vs: String, fs: String): Int {
        val vShader = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER)
        GLES20.glShaderSource(vShader, vs)
        GLES20.glCompileShader(vShader)
        val vStatus = IntArray(1)
        GLES20.glGetShaderiv(vShader, GLES20.GL_COMPILE_STATUS, vStatus, 0)
        if (vStatus[0] == 0) throw RuntimeException("V shader failed: ${GLES20.glGetShaderInfoLog(vShader)}")

        val fShader = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER)
        GLES20.glShaderSource(fShader, fs)
        GLES20.glCompileShader(fShader)
        val fStatus = IntArray(1)
        GLES20.glGetShaderiv(fShader, GLES20.GL_COMPILE_STATUS, fStatus, 0)
        if (fStatus[0] == 0) throw RuntimeException("F shader failed: ${GLES20.glGetShaderInfoLog(fShader)}")

        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vShader)
        GLES20.glAttachShader(prog, fShader)
        GLES20.glLinkProgram(prog)
        val link = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, link, 0)
        if (link[0] != GLES20.GL_TRUE) throw RuntimeException("Link failed: ${GLES20.glGetProgramInfoLog(prog)}")
        GLES20.glDeleteShader(vShader)
        GLES20.glDeleteShader(fShader)
        return prog
    }

    override fun process(inputs: Map<String, GLTexture>): GLTexture {
        check(isInitialized) { "Node not initialized" }
        val input = inputs["input"] ?: throw IllegalArgumentException("BlurNode $id requires input")

        val sigma = parameters.getFloat("sigma", 4f).coerceIn(0.1f, 25f)
        var radius = parameters.getInt("radius", 0)
        if (radius <= 0) {
            radius = (sigma * 3f).toInt().coerceIn(1, 25)
        }

        // Resize FBOs if needed
        if (intermediateFBO == null || intermediateFBO!!.width != input.width || intermediateFBO!!.height != input.height) {
            intermediateFBO?.close()
            finalFBO?.close()
            intermediateFBO = GLFramebuffer.create(input.width, input.height)
            finalFBO = GLFramebuffer.create(input.width, input.height)
        }

        val weights = calculateGaussianWeights(sigma, radius)

        // Horizontal pass
        intermediateFBO!!.bind()
        GLES20.glUseProgram(horizontalProgram)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(input.target, input.textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(horizontalProgram, "sTexture"), 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(horizontalProgram, "uRadius"), radius)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(horizontalProgram, "uSigma"), sigma)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(horizontalProgram, "uTexelSize"), 1f / input.width, 1f / input.height)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(horizontalProgram, "uHorizontal"), 1)
        GLES20.glUniform1fv(GLES20.glGetUniformLocation(horizontalProgram, "uWeights"), weights.size, weights, 0)

        drawQuadWithProgram(horizontalProgram)

        // Vertical pass
        finalFBO!!.bind()
        GLES20.glUseProgram(verticalProgram)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(intermediateFBO!!.texture.target, intermediateFBO!!.texture.textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(verticalProgram, "sTexture"), 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(verticalProgram, "uRadius"), radius)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(verticalProgram, "uSigma"), sigma)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(verticalProgram, "uTexelSize"), 1f / input.width, 1f / input.height)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(verticalProgram, "uHorizontal"), 0)
        GLES20.glUniform1fv(GLES20.glGetUniformLocation(verticalProgram, "uWeights"), weights.size, weights, 0)

        drawQuadWithProgram(verticalProgram)

        finalFBO!!.unbind()

        return GLTexture.wrapExisting(finalFBO!!.texture.textureId, finalFBO!!.texture.target, finalFBO!!.width, finalFBO!!.height, owned = false)
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

    private fun calculateGaussianWeights(sigma: Float, radius: Int): FloatArray {
        val size = radius * 2 + 1
        val weights = FloatArray(size)
        var sum = 0f
        for (i in -radius..radius) {
            val w = exp(-(i * i).toFloat() / (2f * sigma * sigma))
            weights[i + radius] = w
            sum += w
        }
        // Normalize
        for (i in weights.indices) weights[i] /= sum
        return weights
    }

    override fun onRelease() {
        intermediateFBO?.close()
        intermediateFBO = null
        finalFBO?.close()
        finalFBO = null
        if (verticalProgram != 0 && verticalProgram != programId) {
            GLES20.glDeleteProgram(verticalProgram)
            verticalProgram = 0
        }
    }

    override fun setInputConnection(inputSocketId: String, fromNodeId: String?, fromOutputId: String?) {}

    companion object {
        private const val FRAGMENT_SHADER_GAUSSIAN = "precision mediump float;\n" +
"            varying vec2 vTexCoord;\n" +
"            \n" +
"            uniform sampler2D sTexture;\n" +
"            uniform int uRadius;\n" +
"            uniform float uSigma;\n" +
"            uniform vec2 uTexelSize;\n" +
"            uniform int uHorizontal;\n" +
"            uniform float uWeights[51]; // max radius 25 => 51 weights\n" +
"\n" +
"            void main() {\n" +
"                vec4 result = vec4(0.0);\n" +
"                // Clamp radius to 25 for loop safety\n" +
"                int r = min(uRadius, 25);\n" +
"                for (int i = -25; i <= 25; i++) {\n" +
"                    if (abs(i) > r) continue;\n" +
"                    vec2 offset;\n" +
"                    if (uHorizontal == 1) {\n" +
"                        offset = vec2(float(i) * uTexelSize.x, 0.0);\n" +
"                    } else {\n" +
"                        offset = vec2(0.0, float(i) * uTexelSize.y);\n" +
"                    }\n" +
"                    float weight = uWeights[i + r];\n" +
"                    result += texture2D(sTexture, vTexCoord + offset) * weight;\n" +
"                }\n" +
"                gl_FragColor = result;\n" +
"            }\n"
    }
}

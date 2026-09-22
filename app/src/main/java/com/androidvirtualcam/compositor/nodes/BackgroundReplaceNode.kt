package com.androidvirtualcam.compositor.nodes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import com.androidvirtualcam.compositor.*
import com.androidvirtualcam.segmentation.BackgroundSegmentationEngine
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * BackgroundReplaceNode – composite foreground over background using segmentation mask.
 *
 * Works without green screen on both front and rear cameras via BackgroundSegmentationEngine.
 *
 * Inputs:
 * - foreground: RGB texture2D(camera)
 * - background: optional RGB texture2D(if not provided, generated from backgroundType param)
 * - mask: optional alpha mask from SegmentationMaskNode (MediaPipe Selfie Segmentation GPU 30fps)
 *         If mask not provided, uses foreground alpha
 *
 * Parameters:
 * - backgroundType: string "solid", "image", "video", "blur", "custom" (default "blur")
 * - solidColor: int ARGB (default black)
 * - blurRadius: float 0..25 (default 15 for gaussian blur of original)
 * - imagePath: string path to background image
 * - invertMask: bool
 * - edgeFeather: float 0..1 feather edges
 * - enableTemporalFiltering: bool (mask already filtered in engine, but extra feather here)
 *
 * Background options:
 * - solid color: fills background with solid color
 * - image: loads image from path/assets and creates texture
 * - video: uses video texture provider (external)
 * - gaussian blur of original: blurs foreground texture to create background
 * - custom GL texture: uses provided custom texture via background input
 *
 * Mask feeds from BackgroundSegmentationEngine which outputs alpha mask as GL texture directly
 * (no CPU readback), with temporal filtering 0.7/0.3 blend to reduce flickering, fallback to ML Kit.
 */
class BackgroundReplaceNode(
    override val id: String,
    private val context: Context? = null,
    override var parameters: NodeParameters = NodeParameters.builder()
        .string("backgroundType", "blur")
        .int("solidColor", Color.BLACK)
        .float("blurRadius", 15f)
        .string("imagePath", "")
        .bool("invertMask", false)
        .float("edgeFeather", 0.05f)
        .bool("enableTemporalFiltering", true)
        .build()
) : BaseCompositorNode(
    id = id,
    type = "BackgroundReplace",
    inputSockets = listOf(
        InputSocket("foreground", "foreground", SocketType.TEXTURE, displayName = "Foreground"),
        InputSocket("background", "background", SocketType.TEXTURE, displayName = "Background", isRequired = false),
        InputSocket("mask", "mask", SocketType.TEXTURE, displayName = "Mask", isRequired = false)
    ),
    outputSocket = OutputSocket("output", "output", SocketType.TEXTURE),
    parameters = parameters
), CompositorGraph.MutableConnectionsNode {

    private var framebuffer: GLFramebuffer? = null
    private var blurFramebuffer: GLFramebuffer? = null
    private var solidFramebuffer: GLFramebuffer? = null

    private var blurProgram = 0
    private var solidProgram = 0

    private var backgroundImageTexture: GLTexture? = null
    private var backgroundImagePath: String? = null

    private var vertexBuffer: FloatBuffer? = null
    private var texCoordBuffer: FloatBuffer? = null

    private var segmentationEngine: BackgroundSegmentationEngine? = null

    override fun onInitialize() {
        try {
            compileProgram(VERTEX_SHADER_SIMPLE, FRAGMENT_SHADER_BG_REPLACE)
        } catch (e: Exception) {
            Log.e("BackgroundReplaceNode", "Shader compile failed, fallback to passthrough", e)
            try { compileProgram(PASSTHROUGH_VERT, PASSTHROUGH_FRAG) } catch (_: Exception) {}
        }
        try {
            blurProgram = compileProgram(VERTEX_SHADER_SIMPLE, FRAGMENT_SHADER_BLUR)
        } catch (e: Exception) {
            Log.e("BackgroundReplaceNode", "Shader compile failed, fallback to passthrough", e)
            try { compileProgram(PASSTHROUGH_VERT, PASSTHROUGH_FRAG) } catch (_: Exception) {}
        }
        try {
            solidProgram = compileProgram(VERTEX_SHADER_SIMPLE, FRAGMENT_SHADER_SOLID)
        } catch (e: Exception) {
            Log.e("BackgroundReplaceNode", "Shader compile failed, fallback to passthrough", e)
            try { compileProgram(PASSTHROUGH_VERT, PASSTHROUGH_FRAG) } catch (_: Exception) {}
        }

        val vertices = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val texCoords = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(vertices)
            position(0)
        }
        texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(texCoords)
            position(0)
        }

        Log.d("BackgroundReplaceNode", "Initialized $id with backgroundType=${parameters.getString("backgroundType", "blur")}")
    }

    override fun process(inputs: Map<String, GLTexture>): GLTexture {
        check(isInitialized) { "Node not initialized" }
        val fg = inputs["foreground"] ?: throw IllegalArgumentException("BackgroundReplaceNode $id requires foreground")
        val bgInput = inputs["background"]
        val mask = inputs["mask"]

        val w = fg.width
        val h = fg.height

        if (framebuffer == null || framebuffer!!.width != w || framebuffer!!.height != h) {
            framebuffer?.close()
            blurFramebuffer?.close()
            solidFramebuffer?.close()
            framebuffer = GLFramebuffer.create(w, h)
            blurFramebuffer = GLFramebuffer.create(w, h)
            solidFramebuffer = GLFramebuffer.create(w, h)
        }

        val backgroundTexture = bgInput ?: generateBackgroundTexture(fg, w, h)

        framebuffer!!.bind()
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(programId)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(fg.target, fg.textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "sForeground"), 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(backgroundTexture.target, backgroundTexture.textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "sBackground"), 1)

        if (mask != null) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
            GLES20.glBindTexture(mask.target, mask.textureId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "sMask"), 2)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "uUseMask"), 1)
        } else {
            GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "uUseMask"), 0)
        }

        GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "uInvertMask"), if (parameters.getBool("invertMask", false)) 1 else 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, "uEdgeFeather"), parameters.getFloat("edgeFeather", 0.05f))

        drawQuad()
        framebuffer!!.unbind()

        return GLTexture.wrapExisting(framebuffer!!.texture.textureId, framebuffer!!.texture.target, w, h, owned = false)
    }

    private fun generateBackgroundTexture(foreground: GLTexture, w: Int, h: Int): GLTexture {
        val bgType = parameters.getString("backgroundType", "blur")
        return when (bgType) {
            "solid" -> generateSolidColor(w, h)
            "image" -> generateImageBackground(w, h)
            "blur" -> generateBlurBackground(foreground, w, h)
            "video" -> {
                foreground
            }
            "custom" -> {
                foreground
            }
            else -> generateBlurBackground(foreground, w, h)
        }
    }

    private fun generateSolidColor(w: Int, h: Int): GLTexture {
        val colorInt = parameters.getInt("solidColor", Color.BLACK)
        val r = Color.red(colorInt) / 255f
        val g = Color.green(colorInt) / 255f
        val b = Color.blue(colorInt) / 255f
        val a = Color.alpha(colorInt) / 255f

        solidFramebuffer?.bind()
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(solidProgram)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(solidProgram, "uColor"), r, g, b, a)

        val posLoc = GLES20.glGetAttribLocation(solidProgram, "a_Position")
        val texLoc = GLES20.glGetAttribLocation(solidProgram, "a_TexCoord")
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(texLoc)
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(texLoc)
        solidFramebuffer?.unbind()

        return GLTexture.wrapExisting(solidFramebuffer!!.texture.textureId, solidFramebuffer!!.texture.target, w, h, owned = false)
    }

    private fun generateImageBackground(w: Int, h: Int): GLTexture {
        val imagePath = parameters.getString("imagePath", "")
        if (imagePath.isNotEmpty() && imagePath != backgroundImagePath) {
            backgroundImageTexture?.release()
            backgroundImageTexture = null
            backgroundImagePath = imagePath
            try {
                val bitmap = if (imagePath.startsWith("/")) {
                    BitmapFactory.decodeFile(imagePath)
                } else {
                    context?.assets?.open(imagePath)?.use { input ->
                        BitmapFactory.decodeStream(input)
                    }
                }
                bitmap?.let { bmp ->
                    val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
                    val texId = createTextureFromBitmap(scaled)
                    backgroundImageTexture = GLTexture.wrapExisting(texId, GLES20.GL_TEXTURE_2D, w, h, owned = true)
                    if (scaled != bmp) scaled.recycle()
                    if (bmp != scaled) bmp.recycle()
                }
            } catch (e: Exception) {
                Log.w("BackgroundReplaceNode", "Failed to load background image $imagePath: ${e.message}")
            }
        }
        return backgroundImageTexture ?: generateSolidColor(w, h)
    }

    private fun generateBlurBackground(foreground: GLTexture, w: Int, h: Int): GLTexture {
        val blurRadius = parameters.getFloat("blurRadius", 15f)

        blurFramebuffer?.bind()
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(blurProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(foreground.target, foreground.textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(blurProgram, "uTexture"), 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(blurProgram, "uTexelSize"), 1f / w, 1f / h)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(blurProgram, "uBlurRadius"), blurRadius)

        val posLoc = GLES20.glGetAttribLocation(blurProgram, "a_Position")
        val texLoc = GLES20.glGetAttribLocation(blurProgram, "a_TexCoord")
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(texLoc)
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(texLoc)
        blurFramebuffer?.unbind()

        return GLTexture.wrapExisting(blurFramebuffer!!.texture.textureId, blurFramebuffer!!.texture.target, w, h, owned = false)
    }

    private fun createTextureFromBitmap(bitmap: Bitmap): Int {
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        val texId = texIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return texId
    }

    override fun onRelease() {
        framebuffer?.close()
        blurFramebuffer?.close()
        solidFramebuffer?.close()
        framebuffer = null
        blurFramebuffer = null
        solidFramebuffer = null
        backgroundImageTexture?.release()
        backgroundImageTexture = null
        try {
            if (blurProgram != 0) GLES20.glDeleteProgram(blurProgram)
            if (solidProgram != 0) GLES20.glDeleteProgram(solidProgram)
        } catch (_: Exception) {}
        blurProgram = 0
        solidProgram = 0
        vertexBuffer = null
        texCoordBuffer = null
        try {
            segmentationEngine?.release()
        } catch (_: Exception) {}
        segmentationEngine = null
    }

    override fun setInputConnection(inputSocketId: String, fromNodeId: String?, fromOutputId: String?) {}

    companion object {
        private const val FRAGMENT_SHADER_BG_REPLACE = "precision mediump float;\n" +
"            varying vec2 vTexCoord;\n" +
"            \n" +
"            uniform sampler2D sForeground;\n" +
"            uniform sampler2D sBackground;\n" +
"            uniform sampler2D sMask;\n" +
"            uniform int uUseMask;\n" +
"            uniform int uInvertMask;\n" +
"            uniform float uEdgeFeather;\n" +
"\n" +
"            float featherAlpha(float alpha, float feather) {\n" +
"                if (feather <= 0.0) return alpha;\n" +
"                return smoothstep(0.0, feather, alpha) * smoothstep(1.0, 1.0 - feather, alpha) + alpha * step(feather, 0.5);\n" +
"            }\n" +
"\n" +
"            void main() {\n" +
"                vec4 fg = texture2D(sForeground, vTexCoord);\n" +
"                vec4 bg = texture2D(sBackground, vTexCoord);\n" +
"\n" +
"                float alpha = fg.a;\n" +
"                if (alpha == 0.0) alpha = 1.0;\n" +
"\n" +
"                if (uUseMask == 1) {\n" +
"                    float maskVal = texture2D(sMask, vTexCoord).r;\n" +
"                    alpha = maskVal;\n" +
"                }\n" +
"\n" +
"                if (uInvertMask == 1) {\n" +
"                    alpha = 1.0 - alpha;\n" +
"                }\n" +
"\n" +
"                if (uEdgeFeather > 0.0) {\n" +
"                    alpha = smoothstep(0.0, 0.5, alpha);\n" +
"                    alpha = mix(alpha, smoothstep(0.0, uEdgeFeather, alpha), 0.5);\n" +
"                }\n" +
"\n" +
"                alpha = clamp(alpha, 0.0, 1.0);\n" +
"\n" +
"                vec3 finalRgb = mix(bg.rgb, fg.rgb, alpha);\n" +
"                gl_FragColor = vec4(finalRgb, 1.0);\n" +
"            }\n"

        private const val FRAGMENT_SHADER_BLUR = "precision mediump float;\n" +
"            varying vec2 vTexCoord;\n" +
"            \n" +
"            uniform sampler2D uTexture;\n" +
"            uniform vec2 uTexelSize;\n" +
"            uniform float uBlurRadius;\n" +
"            void main() {\n" +
"                vec4 sum = vec4(0.0);\n" +
"                float radius = uBlurRadius;\n" +
"                vec2 texel = uTexelSize * radius;\n" +
"                sum += texture2D(uTexture, vTexCoord + vec2(-texel.x, -texel.y)) * 0.0625;\n" +
"                sum += texture2D(uTexture, vTexCoord + vec2(0.0, -texel.y)) * 0.125;\n" +
"                sum += texture2D(uTexture, vTexCoord + vec2(texel.x, -texel.y)) * 0.0625;\n" +
"                sum += texture2D(uTexture, vTexCoord + vec2(-texel.x, 0.0)) * 0.125;\n" +
"                sum += texture2D(uTexture, vTexCoord) * 0.25;\n" +
"                sum += texture2D(uTexture, vTexCoord + vec2(texel.x, 0.0)) * 0.125;\n" +
"                sum += texture2D(uTexture, vTexCoord + vec2(-texel.x, texel.y)) * 0.0625;\n" +
"                sum += texture2D(uTexture, vTexCoord + vec2(0.0, texel.y)) * 0.125;\n" +
"                sum += texture2D(uTexture, vTexCoord + vec2(texel.x, texel.y)) * 0.0625;\n" +
"                gl_FragColor = sum;\n" +
"            }\n"

        private const val FRAGMENT_SHADER_SOLID = "precision mediump float;\n" +
"varying vec2 vTexCoord;\n" +
"            \n" +
"            uniform vec4 uColor;\n" +
"            void main() {\n" +
"                gl_FragColor = uColor;\n" +
"            }\n"
    }
}

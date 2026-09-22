package com.androidvirtualcam.compositor.nodes

import android.graphics.*
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import com.androidvirtualcam.compositor.*
import kotlin.math.sin

/**
 * TextOverlayNode – Compose Canvas → Bitmap → GL each frame, slide/fade animations.
 *
 * Inputs:
 * - input: background texture
 *
 * Parameters:
 * - text: string
 * - fontSize: int
 * - color: vec4 (0..1)
 * - backgroundColor: vec4 (0..1, a=0 for transparent)
 * - x: float 0..1 normalized
 * - y: float 0..1 normalized
 * - animation: string None/SlideLeft/SlideRight/Fade/SlideUp/SlideDown
 * - animationDuration: float seconds
 * - animationProgress: float 0..1 (driven externally per frame)
 * - bold, italic, shadow
 *
 * Production: renders text to bitmap each frame via Canvas (Compose Canvas in real UI, but using android.graphics for GL thread),
 * uploads via glTexSubImage2D, composites over input.
 */
class TextOverlayNode(
    override val id: String,
    override var parameters: NodeParameters = NodeParameters.builder()
        .string("text", "Hello World")
        .int("fontSize", 48)
        .vec4("color", 1f, 1f, 1f, 1f)
        .vec4("backgroundColor", 0f, 0f, 0f, 0f)
        .float("x", 0.5f)
        .float("y", 0.9f)
        .string("animation", "None")
        .float("animationDuration", 0.5f)
        .float("animationProgress", 1f)
        .bool("bold", false)
        .bool("shadow", true)
        .build()
) : BaseCompositorNode(
    id = id,
    type = "TextOverlay",
    inputSockets = listOf(
        InputSocket("input", "input", SocketType.TEXTURE, displayName = "Background")
    ),
    outputSocket = OutputSocket("output", "output", SocketType.TEXTURE),
    parameters = parameters
), CompositorGraph.MutableConnectionsNode {

    private var framebuffer: GLFramebuffer? = null
    private var textTexture: GLTexture? = null
    private var textBitmap: Bitmap? = null
    private var canvas: Canvas? = null

    private var bitmapWidth: Int = 1024
    private var bitmapHeight: Int = 256

    override fun onInitialize() {
        try {
            compileProgram(VERTEX_SHADER_SIMPLE, FRAGMENT_SHADER_TEXT)
        } catch (e: Exception) {
            Log.e("TextOverlayNode", "Shader compile failed, fallback to passthrough", e)
            try { compileProgram(PASSTHROUGH_VERT, PASSTHROUGH_FRAG) } catch (_: Exception) {}
        }

        // Create bitmap for text rendering – reuse
        bitmapWidth = parameters.getInt("bitmapWidth", 1024)
        bitmapHeight = parameters.getInt("bitmapHeight", 256)
        textBitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        canvas = Canvas(textBitmap!!)

        // Create GL texture for text
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        val texId = texIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        // Allocate empty
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, bitmapWidth, bitmapHeight, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        textTexture = GLTexture(texId, GLES20.GL_TEXTURE_2D, bitmapWidth, bitmapHeight, GLES20.GL_RGBA, isOes = false, isOwned = true)

        Log.d("TextOverlayNode", "Initialized $id bitmap ${bitmapWidth}x${bitmapHeight}")
    }

    override fun process(inputs: Map<String, GLTexture>): GLTexture {
        check(isInitialized) { "Node not initialized" }
        val input = inputs["input"] ?: throw IllegalArgumentException("TextOverlayNode $id requires input")

        if (framebuffer == null || framebuffer!!.width != input.width || framebuffer!!.height != input.height) {
            framebuffer?.close()
            framebuffer = GLFramebuffer.create(input.width, input.height)
        }

        // Update text bitmap each frame
        updateTextBitmap()

        // Upload bitmap to GL
        textTexture?.let { tex ->
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex.textureId)
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, textBitmap!!)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        }

        // Composite
        framebuffer!!.bind()
        GLES20.glUseProgram(programId)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(input.target, input.textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "sBackground"), 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textTexture!!.textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "sText"), 1)

        val x = parameters.getFloat("x", 0.5f)
        val y = parameters.getFloat("y", 0.9f)
        val anim = parameters.getString("animation", "None")
        val progress = parameters.getFloat("animationProgress", 1f)

        GLES20.glUniform2f(GLES20.glGetUniformLocation(programId, "uPosition"), x, y)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, "uAnimProgress"), progress)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "uAnimType"), animationTypeToInt(anim))
        // Text quad size relative to screen – aspect ratio preserved
        GLES20.glUniform2f(GLES20.glGetUniformLocation(programId, "uTextSize"), bitmapWidth.toFloat() / input.width, bitmapHeight.toFloat() / input.height)

        drawQuad()
        framebuffer!!.unbind()

        return GLTexture.wrapExisting(framebuffer!!.texture.textureId, framebuffer!!.texture.target, framebuffer!!.width, framebuffer!!.height, owned = false)
    }

    private fun animationTypeToInt(anim: String): Int = when (anim) {
        "SlideLeft" -> 1
        "SlideRight" -> 2
        "Fade" -> 3
        "SlideUp" -> 4
        "SlideDown" -> 5
        else -> 0 // None
    }

    private fun updateTextBitmap() {
        val bmp = textBitmap ?: return
        val c = canvas ?: return

        // Clear
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        val text = parameters.getString("text", "Hello")
        val fontSize = parameters.getInt("fontSize", 48)
        val colorVec = parameters.getVec4("color", listOf(1f,1f,1f,1f))
        val bgColorVec = parameters.getVec4("backgroundColor", listOf(0f,0f,0f,0f))
        val bold = parameters.getBool("bold", false)
        val shadow = parameters.getBool("shadow", true)

        // Background rounded rect if alpha > 0
        if (bgColorVec[3] > 0.01f) {
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(
                    (bgColorVec[3]*255).toInt().coerceIn(0,255),
                    (bgColorVec[0]*255).toInt().coerceIn(0,255),
                    (bgColorVec[1]*255).toInt().coerceIn(0,255),
                    (bgColorVec[2]*255).toInt().coerceIn(0,255)
                )
            }
            val rect = RectF(0f, 0f, bitmapWidth.toFloat(), bitmapHeight.toFloat())
            c.drawRoundRect(rect, 20f, 20f, bgPaint)
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(
                (colorVec[3]*255).toInt().coerceIn(0,255),
                (colorVec[0]*255).toInt().coerceIn(0,255),
                (colorVec[1]*255).toInt().coerceIn(0,255),
                (colorVec[2]*255).toInt().coerceIn(0,255)
            )
            textSize = fontSize.toFloat()
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            textAlign = Paint.Align.CENTER
        }

        if (shadow) {
            paint.setShadowLayer(4f, 2f, 2f, Color.BLACK)
        }

        // Center text
        val xPos = bitmapWidth / 2f
        val yPos = bitmapHeight / 2f - (paint.descent() + paint.ascent()) / 2f
        c.drawText(text, xPos, yPos, paint)
    }

    override fun onRelease() {
        framebuffer?.close()
        framebuffer = null
        textTexture?.release()
        textTexture = null
        textBitmap?.recycle()
        textBitmap = null
        canvas = null
    }

    override fun setInputConnection(inputSocketId: String, fromNodeId: String?, fromOutputId: String?) {}

    companion object {
        private const val FRAGMENT_SHADER_TEXT = "precision mediump float;\n" +
"            varying vec2 vTexCoord;\n" +
"            \n" +
"            uniform sampler2D sBackground;\n" +
"            uniform sampler2D sText;\n" +
"            uniform vec2 uPosition; // normalized center 0..1\n" +
"            uniform float uAnimProgress; // 0..1\n" +
"            uniform int uAnimType;\n" +
"            uniform vec2 uTextSize; // size relative to screen\n" +
"\n" +
"            void main() {\n" +
"                vec4 bg = texture2D(sBackground, vTexCoord);\n" +
"\n" +
"                // Calculate text UV based on position and size\n" +
"                vec2 textCenter = uPosition;\n" +
"                vec2 textMin = textCenter - uTextSize * 0.5;\n" +
"                vec2 textMax = textCenter + uTextSize * 0.5;\n" +
"\n" +
"                // Animation offsets\n" +
"                vec2 animOffset = vec2(0.0);\n" +
"                float alphaMul = 1.0;\n" +
"\n" +
"                if (uAnimType == 1) { // SlideLeft\n" +
"                    animOffset.x = (1.0 - uAnimProgress) * 1.0;\n" +
"                } else if (uAnimType == 2) { // SlideRight\n" +
"                    animOffset.x = (1.0 - uAnimProgress) * -1.0;\n" +
"                } else if (uAnimType == 3) { // Fade\n" +
"                    alphaMul = uAnimProgress;\n" +
"                } else if (uAnimType == 4) { // SlideUp\n" +
"                    animOffset.y = (1.0 - uAnimProgress) * 0.5;\n" +
"                } else if (uAnimType == 5) { // SlideDown\n" +
"                    animOffset.y = (1.0 - uAnimProgress) * -0.5;\n" +
"                }\n" +
"\n" +
"                vec2 samplePos = vTexCoord - animOffset;\n" +
"                vec2 textUV = (samplePos - textMin) / (textMax - textMin);\n" +
"\n" +
"                // Check if inside text quad\n" +
"                if (textUV.x >= 0.0 && textUV.x <= 1.0 && textUV.y >= 0.0 && textUV.y <= 1.0) {\n" +
"                    // Flip Y because bitmap origin top-left vs GL bottom-left\n" +
"                    textUV.y = 1.0 - textUV.y;\n" +
"                    vec4 textColor = texture2D(sText, textUV);\n" +
"                    // Blend text over background using text alpha\n" +
"                    float a = textColor.a * alphaMul;\n" +
"                    vec3 finalRgb = mix(bg.rgb, textColor.rgb, a);\n" +
"                    gl_FragColor = vec4(finalRgb, 1.0);\n" +
"                } else {\n" +
"                    gl_FragColor = bg;\n" +
"                }\n" +
"            }\n"
    }
}

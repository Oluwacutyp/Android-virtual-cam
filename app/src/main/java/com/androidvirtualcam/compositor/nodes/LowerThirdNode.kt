package com.androidvirtualcam.compositor.nodes

import android.graphics.*
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import com.androidvirtualcam.compositor.*
import kotlin.math.min

/**
 * LowerThirdNode – 4 styles Modern/Broadcast/Minimal/Sports animate on/off duration.
 *
 * Inputs:
 * - input: background
 *
 * Parameters:
 * - title: string
 * - subtitle: string
 * - style: string Modern/Broadcast/Minimal/Sports
 * - primaryColor: vec3
 * - secondaryColor: vec3
 * - textColor: vec3
 * - show: bool
 * - animationProgress: float 0..1 (0 hidden, 1 visible) driven externally
 * - duration: float seconds visible
 * - position: string Bottom/Top
 *
 * Production: renders lower third to bitmap via Canvas (would be Compose in UI) with 4 styles,
 * uploads to GL texture each frame, composites.
 */
class LowerThirdNode(
    override val id: String,
    override var parameters: NodeParameters = NodeParameters.builder()
        .string("title", "John Doe")
        .string("subtitle", "Host")
        .string("style", "Modern")
        .vec3("primaryColor", 0.1f, 0.4f, 0.9f)
        .vec3("secondaryColor", 0.9f, 0.2f, 0.2f)
        .vec3("textColor", 1f, 1f, 1f)
        .bool("show", true)
        .float("animationProgress", 1f)
        .float("duration", 5f)
        .string("position", "Bottom")
        .build()
) : BaseCompositorNode(
    id = id,
    type = "LowerThird",
    inputSockets = listOf(
        InputSocket("input", "input", SocketType.TEXTURE, displayName = "Background")
    ),
    outputSocket = OutputSocket("output", "output", SocketType.TEXTURE),
    parameters = parameters
), CompositorGraph.MutableConnectionsNode {

    private var framebuffer: GLFramebuffer? = null
    private var ltTexture: GLTexture? = null
    private var ltBitmap: Bitmap? = null
    private var canvas: Canvas? = null

    private var bitmapWidth: Int = 1920
    private var bitmapHeight: Int = 400

    override fun onInitialize() {
        try {
            compileProgram(VERTEX_SHADER_SIMPLE, FRAGMENT_SHADER_LT)
        } catch (e: Exception) {
            Log.e("LowerThirdNode", "Shader compile failed, fallback to passthrough", e)
            try { compileProgram(PASSTHROUGH_VERT, PASSTHROUGH_FRAG) } catch (_: Exception) {}
        }

        bitmapWidth = parameters.getInt("bitmapWidth", 1920)
        bitmapHeight = parameters.getInt("bitmapHeight", 400)
        ltBitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        canvas = Canvas(ltBitmap!!)

        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        val texId = texIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, bitmapWidth, bitmapHeight, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        ltTexture = GLTexture(texId, GLES20.GL_TEXTURE_2D, bitmapWidth, bitmapHeight, GLES20.GL_RGBA, isOes = false, isOwned = true)

        Log.d("LowerThirdNode", "Initialized $id style=${parameters.getString("style")}")
    }

    override fun process(inputs: Map<String, GLTexture>): GLTexture {
        check(isInitialized) { "Node not initialized" }
        val input = inputs["input"] ?: throw IllegalArgumentException("LowerThirdNode $id requires input")

        if (framebuffer == null || framebuffer!!.width != input.width || framebuffer!!.height != input.height) {
            framebuffer?.close()
            framebuffer = GLFramebuffer.create(input.width, input.height)
        }

        updateLowerThirdBitmap()

        ltTexture?.let { tex ->
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex.textureId)
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, ltBitmap!!)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        }

        framebuffer!!.bind()
        GLES20.glUseProgram(programId)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(input.target, input.textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "sBackground"), 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ltTexture!!.textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "sLowerThird"), 1)

        val show = parameters.getBool("show", true)
        val progress = parameters.getFloat("animationProgress", 1f)
        val position = parameters.getString("position", "Bottom")

        GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "uShow"), if (show) 1 else 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, "uProgress"), progress)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "uPositionTop"), if (position == "Top") 1 else 0)

        drawQuad()
        framebuffer!!.unbind()

        return GLTexture.wrapExisting(framebuffer!!.texture.textureId, framebuffer!!.texture.target, framebuffer!!.width, framebuffer!!.height, owned = false)
    }

    private fun updateLowerThirdBitmap() {
        val bmp = ltBitmap ?: return
        val c = canvas ?: return
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        val title = parameters.getString("title", "John Doe")
        val subtitle = parameters.getString("subtitle", "Host")
        val style = parameters.getString("style", "Modern")
        val primary = parameters.getVec3("primaryColor", listOf(0.1f,0.4f,0.9f))
        val secondary = parameters.getVec3("secondaryColor", listOf(0.9f,0.2f,0.2f))
        val textColor = parameters.getVec3("textColor", listOf(1f,1f,1f))

        val primaryInt = Color.rgb((primary[0]*255).toInt().coerceIn(0,255), (primary[1]*255).toInt().coerceIn(0,255), (primary[2]*255).toInt().coerceIn(0,255))
        val secondaryInt = Color.rgb((secondary[0]*255).toInt().coerceIn(0,255), (secondary[1]*255).toInt().coerceIn(0,255), (secondary[2]*255).toInt().coerceIn(0,255))
        val textInt = Color.rgb((textColor[0]*255).toInt().coerceIn(0,255), (textColor[1]*255).toInt().coerceIn(0,255), (textColor[2]*255).toInt().coerceIn(0,255))

        val progress = parameters.getFloat("animationProgress", 1f)

        when (style) {
            "Modern" -> drawModern(c, title, subtitle, primaryInt, textInt, progress)
            "Broadcast" -> drawBroadcast(c, title, subtitle, primaryInt, secondaryInt, textInt, progress)
            "Minimal" -> drawMinimal(c, title, subtitle, primaryInt, textInt, progress)
            "Sports" -> drawSports(c, title, subtitle, primaryInt, secondaryInt, textInt, progress)
            else -> drawModern(c, title, subtitle, primaryInt, textInt, progress)
        }
    }

    private fun drawModern(canvas: Canvas, title: String, subtitle: String, primary: Int, textColor: Int, progress: Float) {
        val w = bitmapWidth.toFloat()
        val h = bitmapHeight.toFloat()
        val barHeight = h * 0.6f
        val barWidth = w * 0.6f * progress.coerceIn(0f,1f)
        val y = h * 0.2f

        // Background bar with rounded corners
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = primary; alpha = 230 }
        val rect = RectF(0f, y, barWidth, y + barHeight)
        canvas.drawRoundRect(rect, 16f, 16f, paint)

        // Accent line
        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; alpha = 200 }
        canvas.drawRect(RectF(0f, y, 12f * progress, y + barHeight), accentPaint)

        // Title
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = 48f
            typeface = Typeface.DEFAULT_BOLD
        }
        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = 32f
            alpha = 200
        }
        if (progress > 0.2f) {
            canvas.drawText(title, 40f, y + 60f, titlePaint)
            canvas.drawText(subtitle, 40f, y + 100f, subtitlePaint)
        }
    }

    private fun drawBroadcast(canvas: Canvas, title: String, subtitle: String, primary: Int, secondary: Int, textColor: Int, progress: Float) {
        val w = bitmapWidth.toFloat()
        val h = bitmapHeight.toFloat()
        val barWidth = w * 0.5f * progress
        val y = h * 0.15f

        // Two-tone design – like news channel
        val primaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = primary }
        val secondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = secondary }

        canvas.drawRect(RectF(0f, y, barWidth, y + 70f), primaryPaint)
        canvas.drawRect(RectF(0f, y + 70f, barWidth * 0.9f, y + 110f), secondaryPaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 44f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 28f
        }
        if (progress > 0.3f) {
            canvas.drawText(title.uppercase(), 20f, y + 45f, titlePaint)
            canvas.drawText(subtitle, 20f, y + 95f, subPaint)
        }

        // Live indicator dot
        if (progress > 0.5f) {
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED }
            canvas.drawCircle(barWidth - 20f, y + 20f, 8f, dotPaint)
        }
    }

    private fun drawMinimal(canvas: Canvas, title: String, subtitle: String, primary: Int, textColor: Int, progress: Float) {
        val w = bitmapWidth.toFloat()
        val h = bitmapHeight.toFloat()
        val y = h * 0.3f

        // Minimal – just line and text
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = primary
            strokeWidth = 4f
        }
        val lineWidth = 200f * progress
        canvas.drawLine(40f, y, 40f + lineWidth, y, linePaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = 42f
            typeface = Typeface.DEFAULT_BOLD
            alpha = (progress * 255).toInt().coerceIn(0,255)
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = 28f
            alpha = (progress * 180).toInt().coerceIn(0,255)
        }
        canvas.drawText(title, 40f, y + 50f, titlePaint)
        canvas.drawText(subtitle, 40f, y + 85f, subPaint)
    }

    private fun drawSports(canvas: Canvas, title: String, subtitle: String, primary: Int, secondary: Int, textColor: Int, progress: Float) {
        val w = bitmapWidth.toFloat()
        val h = bitmapHeight.toFloat()
        val y = h * 0.1f
        val barWidth = w * 0.7f * progress

        // Sports – angled/skewed design
        val path = Path().apply {
            moveTo(0f, y)
            lineTo(barWidth, y)
            lineTo(barWidth - 30f, y + 120f)
            lineTo(0f, y + 120f)
            close()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = primary }
        canvas.drawPath(path, paint)

        // Number box
        val numPath = Path().apply {
            moveTo(0f, y)
            lineTo(100f, y)
            lineTo(80f, y + 120f)
            lineTo(0f, y + 120f)
            close()
        }
        val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = secondary }
        canvas.drawPath(numPath, numPaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 50f
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 30f
        }
        val numPaintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 60f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        if (progress > 0.2f) {
            canvas.drawText("10", 40f, y + 80f, numPaintText)
            canvas.drawText(title, 120f, y + 60f, titlePaint)
            canvas.drawText(subtitle, 120f, y + 95f, subPaint)
        }
    }

    override fun onRelease() {
        framebuffer?.close()
        framebuffer = null
        ltTexture?.release()
        ltTexture = null
        ltBitmap?.recycle()
        ltBitmap = null
        canvas = null
    }

    override fun setInputConnection(inputSocketId: String, fromNodeId: String?, fromOutputId: String?) {}

    companion object {
        private const val FRAGMENT_SHADER_LT = "precision mediump float;\n" +
"            varying vec2 vTexCoord;\n" +
"            \n" +
"            uniform sampler2D sBackground;\n" +
"            uniform sampler2D sLowerThird;\n" +
"            uniform int uShow;\n" +
"            uniform float uProgress;\n" +
"            uniform int uPositionTop;\n" +
"\n" +
"            void main() {\n" +
"                vec4 bg = texture2D(sBackground, vTexCoord);\n" +
"\n" +
"                if (uShow == 0 || uProgress <= 0.0) {\n" +
"                    gl_FragColor = bg;\n" +
"                    return;\n" +
"                }\n" +
"\n" +
"                // Lower third occupies bottom (or top) portion\n" +
"                // lt texture is full width 1920x400, we map it to bottom 400px of screen\n" +
"                // vTexCoord y 0=bottom, 1=top? Our quad has 0 bottom, 1 top (OpenGL)\n" +
"                // But bitmap drawn top-left – we flip\n" +
"\n" +
"                float ltHeightNorm = 400.0 / 720.0; // assume 720p, but we use relative\n" +
"                // Actually we want to map: if bottom, lt covers y 0..0.25; if top, y 0.75..1.0\n" +
"                vec2 ltUV;\n" +
"                float inside = 0.0;\n" +
"\n" +
"                if (uPositionTop == 1) {\n" +
"                    // Top position\n" +
"                    if (vTexCoord.y > (1.0 - ltHeightNorm)) {\n" +
"                        ltUV = vec2(vTexCoord.x, (vTexCoord.y - (1.0 - ltHeightNorm)) / ltHeightNorm);\n" +
"                        ltUV.y = 1.0 - ltUV.y; // flip\n" +
"                        inside = 1.0;\n" +
"                    }\n" +
"                } else {\n" +
"                    // Bottom\n" +
"                    if (vTexCoord.y < ltHeightNorm) {\n" +
"                        ltUV = vec2(vTexCoord.x, vTexCoord.y / ltHeightNorm);\n" +
"                        ltUV.y = 1.0 - ltUV.y;\n" +
"                        inside = 1.0;\n" +
"                    }\n" +
"                }\n" +
"\n" +
"                if (inside > 0.5) {\n" +
"                    vec4 lt = texture2D(sLowerThird, ltUV);\n" +
"                    // Slide animation – progress affects alpha and position already in bitmap, but we add fade\n" +
"                    float alpha = lt.a * clamp(uProgress * 1.2, 0.0, 1.0);\n" +
"                    vec3 finalRgb = mix(bg.rgb, lt.rgb, alpha);\n" +
"                    gl_FragColor = vec4(finalRgb, 1.0);\n" +
"                } else {\n" +
"                    gl_FragColor = bg;\n" +
"                }\n" +
"            }\n"
    }
}

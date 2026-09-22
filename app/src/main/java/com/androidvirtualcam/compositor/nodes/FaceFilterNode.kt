package com.androidvirtualcam.compositor.nodes

import android.content.Context
import android.graphics.*
import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import com.androidvirtualcam.compositor.*
import com.androidvirtualcam.face.FaceLandmarkManager

/**
 * FaceFilterNode – Snapchat-style face filters using ML Kit Face Mesh
 * Renders sunglasses, hats, masks, dog ears, etc. as compositor nodes on top of face
 * 
 * Uses face landmarks from ML Kit: eyes, nose, mouth, ears, contours
 * 
 * Filter types:
 * - sunglasses: dark lenses at eye positions
 * - hat: cap/beanie above head
 * - mask: covering nose/mouth
 * - dog: dog ears + nose
 * - cat: cat ears + whiskers
 * - clown: red nose + makeup
 * - beauty: combined skin smoothing + eye brightening
 * - glasses: regular glasses
 * - mustache: mustache at mouth
 * - crown: crown above head
 * 
 * Parameters:
 * - filterType: string enum
 * - intensity: float 0..1
 * - color: vec3 filter color tint
 */
class FaceFilterNode(
    override val id: String,
    private val context: Context,
    override var parameters: NodeParameters = NodeParameters.builder()
        .string("filterType", "sunglasses")
        .float("intensity", 1f)
        .vec3("color", 0f, 0f, 0f)
        .bool("autoScale", true)
        .build()
) : BaseCompositorNode(
    id = id,
    type = "FaceFilter",
    inputSockets = listOf(
        InputSocket("input", "input", SocketType.TEXTURE, displayName = "Input")
    ),
    outputSocket = OutputSocket("output", "output", SocketType.TEXTURE),
    parameters = parameters
), CompositorGraph.MutableConnectionsNode {

    private var framebuffer: GLFramebuffer? = null
    private var filterTexture: GLTexture? = null
    private var filterBitmap: Bitmap? = null

    // For dynamic filter texture generation
    private var lastFilterType = ""

    override fun onInitialize() {
        try {
            compileProgram(VERTEX_SHADER_SIMPLE, FRAGMENT_SHADER_FILTER)
        } catch (e: Exception) {
            Log.e("FaceFilterNode", "Shader compile failed, fallback to passthrough", e)
            try { compileProgram(PASSTHROUGH_VERT, PASSTHROUGH_FRAG) } catch (_: Exception) {}
        }
        // Pre-generate default filter texture2D(sunglasses)
        generateFilterTexture(parameters.getString("filterType", "sunglasses"))
        Log.d("FaceFilterNode", "Initialized $id filter=${parameters.getString("filterType", "sunglasses")}")
    }

    private fun generateFilterTexture(filterType: String) {
        if (filterType == lastFilterType && filterTexture != null) return
        lastFilterType = filterType

        try {
            filterTexture?.release()
            filterBitmap?.recycle()

            // Generate filter bitmap 512x512 with transparent background
            val size = 512
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            val paint = Paint().apply { isAntiAlias = true }

            when (filterType.lowercase()) {
                "sunglasses" -> drawSunglasses(canvas, paint, size)
                "glasses" -> drawGlasses(canvas, paint, size)
                "hat", "cap" -> drawHat(canvas, paint, size)
                "crown" -> drawCrown(canvas, paint, size)
                "mask" -> drawMask(canvas, paint, size)
                "dog" -> drawDogFilter(canvas, paint, size)
                "cat" -> drawCatFilter(canvas, paint, size)
                "mustache" -> drawMustache(canvas, paint, size)
                "clown" -> drawClown(canvas, paint, size)
                else -> drawSunglasses(canvas, paint, size)
            }

            filterBitmap = bmp
            // Upload to GL texture
            val texId = IntArray(1)
            GLES20.glGenTextures(1, texId, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

            filterTexture = GLTexture.wrapExisting(texId[0], GLES20.GL_TEXTURE_2D, size, size, owned = true)

        } catch (e: Exception) {
            Log.e("FaceFilterNode", "generateFilterTexture failed", e)
        }
    }

    private fun drawSunglasses(canvas: Canvas, paint: Paint, size: Int) {
        val w = size.toFloat()
        val h = size.toFloat()
        // Two lenses
        paint.color = Color.argb(200, 0, 0, 0)
        paint.style = Paint.Style.FILL
        val lensW = w * 0.35f
        val lensH = h * 0.25f
        val leftX = w * 0.1f
        val rightX = w * 0.55f
        val y = h * 0.35f
        canvas.drawRoundRect(RectF(leftX, y, leftX + lensW, y + lensH), 20f, 20f, paint)
        canvas.drawRoundRect(RectF(rightX, y, rightX + lensW, y + lensH), 20f, 20f, paint)
        // Bridge
        canvas.drawRect(RectF(leftX + lensW - 10, y + lensH*0.4f, rightX + 10, y + lensH*0.6f), paint)
        // Frame highlight
        paint.color = Color.argb(100, 255, 255, 255)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        canvas.drawRoundRect(RectF(leftX, y, leftX + lensW, y + lensH), 20f, 20f, paint)
        canvas.drawRoundRect(RectF(rightX, y, rightX + lensW, y + lensH), 20f, 20f, paint)
    }

    private fun drawGlasses(canvas: Canvas, paint: Paint, size: Int) {
        val w = size.toFloat()
        val h = size.toFloat()
        paint.color = Color.argb(180, 30, 30, 30)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 8f
        val lensW = w * 0.35f
        val lensH = h * 0.25f
        val leftX = w * 0.1f
        val rightX = w * 0.55f
        val y = h * 0.35f
        canvas.drawRoundRect(RectF(leftX, y, leftX + lensW, y + lensH), 20f, 20f, paint)
        canvas.drawRoundRect(RectF(rightX, y, rightX + lensW, y + lensH), 20f, 20f, paint)
        canvas.drawLine(leftX + lensW, y + lensH*0.5f, rightX, y + lensH*0.5f, paint)
    }

    private fun drawHat(canvas: Canvas, paint: Paint, size: Int) {
        val w = size.toFloat()
        val h = size.toFloat()
        paint.color = Color.argb(255, 200, 50, 50)
        paint.style = Paint.Style.FILL
        // Cap
        val path = Path()
        path.moveTo(w*0.1f, h*0.4f)
        path.lineTo(w*0.5f, h*0.05f)
        path.lineTo(w*0.9f, h*0.4f)
        path.close()
        canvas.drawPath(path, paint)
        paint.color = Color.argb(255, 150, 30, 30)
        canvas.drawRect(RectF(w*0.05f, h*0.35f, w*0.95f, h*0.45f), paint)
    }

    private fun drawCrown(canvas: Canvas, paint: Paint, size: Int) {
        val w = size.toFloat()
        val h = size.toFloat()
        paint.color = Color.argb(255, 255, 215, 0)
        paint.style = Paint.Style.FILL
        val path = Path()
        path.moveTo(w*0.1f, h*0.5f)
        path.lineTo(w*0.2f, h*0.1f)
        path.lineTo(w*0.35f, h*0.3f)
        path.lineTo(w*0.5f, h*0.05f)
        path.lineTo(w*0.65f, h*0.3f)
        path.lineTo(w*0.8f, h*0.1f)
        path.lineTo(w*0.9f, h*0.5f)
        path.close()
        canvas.drawPath(path, paint)
        paint.color = Color.RED
        for (x in listOf(w*0.2f, w*0.5f, w*0.8f)) {
            canvas.drawCircle(x, h*0.15f, 15f, paint)
        }
    }

    private fun drawMask(canvas: Canvas, paint: Paint, size: Int) {
        val w = size.toFloat()
        val h = size.toFloat()
        paint.color = Color.argb(220, 100, 200, 255)
        paint.style = Paint.Style.FILL
        val path = Path()
        path.moveTo(w*0.2f, h*0.5f)
        path.lineTo(w*0.8f, h*0.5f)
        path.lineTo(w*0.7f, h*0.9f)
        path.lineTo(w*0.3f, h*0.9f)
        path.close()
        canvas.drawPath(path, paint)
        paint.color = Color.WHITE
        paint.strokeWidth = 4f
        paint.style = Paint.Style.STROKE
        for (y in listOf(h*0.6f, h*0.7f, h*0.8f)) {
            canvas.drawLine(w*0.25f, y, w*0.75f, y, paint)
        }
    }

    private fun drawDogFilter(canvas: Canvas, paint: Paint, size: Int) {
        val w = size.toFloat()
        val h = size.toFloat()
        // Ears
        paint.color = Color.argb(255, 139, 69, 19)
        paint.style = Paint.Style.FILL
        canvas.drawOval(RectF(w*0.05f, h*0.05f, w*0.3f, h*0.5f), paint)
        canvas.drawOval(RectF(w*0.7f, h*0.05f, w*0.95f, h*0.5f), paint)
        // Nose
        paint.color = Color.BLACK
        canvas.drawOval(RectF(w*0.4f, h*0.5f, w*0.6f, h*0.65f), paint)
    }

    private fun drawCatFilter(canvas: Canvas, paint: Paint, size: Int) {
        val w = size.toFloat()
        val h = size.toFloat()
        paint.color = Color.argb(255, 255, 150, 150)
        paint.style = Paint.Style.FILL
        val path = Path()
        path.moveTo(w*0.15f, h*0.3f)
        path.lineTo(w*0.25f, h*0.05f)
        path.lineTo(w*0.35f, h*0.25f)
        path.close()
        canvas.drawPath(path, paint)
        val path2 = Path()
        path2.moveTo(w*0.65f, h*0.25f)
        path2.lineTo(w*0.75f, h*0.05f)
        path2.lineTo(w*0.85f, h*0.3f)
        path2.close()
        canvas.drawPath(path2, paint)
        // Whiskers
        paint.color = Color.BLACK
        paint.strokeWidth = 3f
        paint.style = Paint.Style.STROKE
        for (y in listOf(h*0.6f, h*0.65f, h*0.7f)) {
            canvas.drawLine(w*0.1f, y, w*0.3f, y, paint)
            canvas.drawLine(w*0.7f, y, w*0.9f, y, paint)
        }
    }

    private fun drawMustache(canvas: Canvas, paint: Paint, size: Int) {
        val w = size.toFloat()
        val h = size.toFloat()
        paint.color = Color.argb(255, 60, 30, 10)
        paint.style = Paint.Style.FILL
        val path = Path()
        path.moveTo(w*0.5f, h*0.5f)
        path.cubicTo(w*0.3f, h*0.4f, w*0.1f, h*0.5f, w*0.2f, h*0.7f)
        path.cubicTo(w*0.3f, h*0.6f, w*0.4f, h*0.6f, w*0.5f, h*0.5f)
        path.cubicTo(w*0.6f, h*0.6f, w*0.7f, h*0.6f, w*0.8f, h*0.7f)
        path.cubicTo(w*0.9f, h*0.5f, w*0.7f, h*0.4f, w*0.5f, h*0.5f)
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun drawClown(canvas: Canvas, paint: Paint, size: Int) {
        val w = size.toFloat()
        val h = size.toFloat()
        paint.color = Color.RED
        paint.style = Paint.Style.FILL
        canvas.drawCircle(w*0.5f, h*0.5f, w*0.15f, paint)
        paint.color = Color.argb(100, 255, 0, 0)
        canvas.drawCircle(w*0.3f, h*0.6f, w*0.1f, paint)
        canvas.drawCircle(w*0.7f, h*0.6f, w*0.1f, paint)
    }

    override fun process(inputs: Map<String, GLTexture>): GLTexture {
        check(isInitialized) { "Node not initialized" }
        val input = inputs["input"] ?: throw IllegalArgumentException("FaceFilterNode $id requires input")

        if (framebuffer == null || framebuffer!!.width != input.width || framebuffer!!.height != input.height) {
            framebuffer?.close()
            framebuffer = GLFramebuffer.create(input.width, input.height)
        }

        val filterType = parameters.getString("filterType", "sunglasses")
        if (filterType != lastFilterType) {
            generateFilterTexture(filterType)
        }

        framebuffer!!.bind()
        GLES20.glUseProgram(programId)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(input.target, input.textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "sTexture"), 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        filterTexture?.let {
            GLES20.glBindTexture(it.target, it.textureId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "sFilter"), 1)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "uHasFilterTex"), 1)
        } ?: run {
            GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "uHasFilterTex"), 0)
        }

        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, "uIntensity"), parameters.getFloat("intensity", 1f))
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "uFilterType"), filterTypeToInt(filterType))

        val faces = FaceLandmarkManager.getFaces()
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "uFaceCount"), faces.size)
        for (i in 0 until minOf(faces.size, 3)) {
            val face = faces[i]
            val prefix = "uFaces[$i]."
            GLES20.glUniform4f(GLES20.glGetUniformLocation(programId, prefix + "rect"), face.boundingBox.left, face.boundingBox.top, face.boundingBox.width(), face.boundingBox.height())
            GLES20.glUniform2f(GLES20.glGetUniformLocation(programId, prefix + "center"), face.center.x, face.center.y)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(programId, prefix + "leftEye"), face.leftEye?.x ?: 0f, face.leftEye?.y ?: 0f)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(programId, prefix + "rightEye"), face.rightEye?.x ?: 0f, face.rightEye?.y ?: 0f)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(programId, prefix + "nose"), face.nose?.x ?: face.center.x, face.nose?.y ?: face.center.y)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(programId, prefix + "mouth"), face.mouthBottom?.x ?: face.center.x, face.mouthBottom?.y ?: face.center.y + face.height*0.2f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, prefix + "eyeDist"), face.eyeDistance)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, prefix + "yaw"), face.headEulerY)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, prefix + "roll"), face.headEulerZ)
        }

        drawQuad()
        framebuffer!!.unbind()

        return GLTexture.wrapExisting(framebuffer!!.texture.textureId, framebuffer!!.texture.target, framebuffer!!.width, framebuffer!!.height, owned = false)
    }

    private fun filterTypeToInt(type: String): Int {
        return when (type.lowercase()) {
            "sunglasses" -> 0
            "glasses" -> 1
            "hat", "cap" -> 2
            "crown" -> 3
            "mask" -> 4
            "dog" -> 5
            "cat" -> 6
            "mustache" -> 7
            "clown" -> 8
            else -> 0
        }
    }

    override fun onRelease() {
        framebuffer?.close()
        framebuffer = null
        filterTexture?.release()
        filterTexture = null
        filterBitmap?.recycle()
        filterBitmap = null
    }

    override fun setInputConnection(inputSocketId: String, fromNodeId: String?, fromOutputId: String?) {}

    companion object {
        private const val FRAGMENT_SHADER_FILTER = "precision mediump float;\n" +
"            varying vec2 vTexCoord;\n" +
"            \n" +
"            uniform sampler2D sTexture;\n" +
"            uniform sampler2D sFilter;\n" +
"            uniform int uHasFilterTex;\n" +
"            uniform float uIntensity;\n" +
"            uniform int uFilterType;\n" +
"            uniform int uFaceCount;\n" +
"\n" +
"            struct Face {\n" +
"                vec4 rect;\n" +
"                vec2 center;\n" +
"                vec2 leftEye;\n" +
"                vec2 rightEye;\n" +
"                vec2 nose;\n" +
"                vec2 mouth;\n" +
"                float eyeDist;\n" +
"                float yaw;\n" +
"                float roll;\n" +
"            };\n" +
"            uniform Face uFaces[3];\n" +
"\n" +
"            // Helper to draw sunglasses procedurally if no texture\n" +
"            vec4 drawSunglasses(vec2 uv, Face face) {\n" +
"                vec2 leftEye = face.leftEye;\n" +
"                vec2 rightEye = face.rightEye;\n" +
"                float eyeDist = face.eyeDist;\n" +
"                if (eyeDist == 0.0) return vec4(0.0);\n" +
"                float lensW = eyeDist * 0.8;\n" +
"                float lensH = eyeDist * 0.5;\n" +
"                // Left lens\n" +
"                vec2 dl = uv - leftEye;\n" +
"                float leftMask = 0.0;\n" +
"                if (abs(dl.x) < lensW*0.5 && abs(dl.y) < lensH*0.5) {\n" +
"                    // rounded rect\n" +
"                    float rx = abs(dl.x) / (lensW*0.5);\n" +
"                    float ry = abs(dl.y) / (lensH*0.5);\n" +
"                    if (rx*rx + ry*ry < 1.2) leftMask = 1.0;\n" +
"                }\n" +
"                vec2 dr = uv - rightEye;\n" +
"                float rightMask = 0.0;\n" +
"                if (abs(dr.x) < lensW*0.5 && abs(dr.y) < lensH*0.5) {\n" +
"                    float rx = abs(dr.x) / (lensW*0.5);\n" +
"                    float ry = abs(dr.y) / (lensH*0.5);\n" +
"                    if (rx*rx + ry*ry < 1.2) rightMask = 1.0;\n" +
"                }\n" +
"                // Bridge\n" +
"                float bridgeMask = 0.0;\n" +
"                vec2 eyeCenter = (leftEye + rightEye) * 0.5;\n" +
"                if (abs(uv.x - eyeCenter.x) < eyeDist*0.2 && abs(uv.y - eyeCenter.y) < lensH*0.15) {\n" +
"                    bridgeMask = 1.0;\n" +
"                }\n" +
"                float mask = max(max(leftMask, rightMask), bridgeMask);\n" +
"                if (mask > 0.5) {\n" +
"                    // Dark lens with slight reflection\n" +
"                    vec3 col = vec3(0.05, 0.05, 0.05);\n" +
"                    // Reflection highlight\n" +
"                    float highlight = 0.0;\n" +
"                    if (uv.y < leftEye.y || uv.y < rightEye.y) highlight = 0.3 * mask;\n" +
"                    col += highlight;\n" +
"                    return vec4(col, mask * 0.9);\n" +
"                }\n" +
"                return vec4(0.0);\n" +
"            }\n" +
"\n" +
"            vec4 drawHat(vec2 uv, Face face) {\n" +
"                vec2 center = face.center;\n" +
"                float w = face.rect.z;\n" +
"                float h = face.rect.w;\n" +
"                // Hat above head\n" +
"                vec2 hatCenter = vec2(center.x, center.y - h*0.6);\n" +
"                vec2 delta = uv - hatCenter;\n" +
"                float hatW = w * 1.2;\n" +
"                float hatH = h * 0.5;\n" +
"                if (abs(delta.x) < hatW*0.5 && delta.y > -hatH*0.5 && delta.y < hatH*0.5) {\n" +
"                    // Simple cap shape\n" +
"                    if (delta.y < 0.0) {\n" +
"                        // Top triangle\n" +
"                        float factor = 1.0 - abs(delta.x) / (hatW*0.5);\n" +
"                        if (delta.y > -hatH*0.5 * factor) {\n" +
"                            return vec4(0.8, 0.2, 0.2, 0.9);\n" +
"                        }\n" +
"                    } else {\n" +
"                        // Brim\n" +
"                        if (abs(delta.x) < hatW*0.6) {\n" +
"                            return vec4(0.6, 0.15, 0.15, 0.9);\n" +
"                        }\n" +
"                    }\n" +
"                }\n" +
"                return vec4(0.0);\n" +
"            }\n" +
"\n" +
"            vec4 drawMask(vec2 uv, Face face) {\n" +
"                vec2 nose = face.nose;\n" +
"                vec2 mouth = face.mouth;\n" +
"                vec4 rect = face.rect;\n" +
"                // Mask covering nose and mouth\n" +
"                if (uv.y > nose.y - rect.w*0.1 && uv.y < mouth.y + rect.w*0.2 && uv.x > rect.x + rect.z*0.1 && uv.x < rect.x + rect.z*0.9) {\n" +
"                    // Simple surgical mask color\n" +
"                    vec3 col = vec3(0.4, 0.8, 1.0);\n" +
"                    // Lines\n" +
"                    float line = 0.0;\n" +
"                    float dy = (uv.y - nose.y) / (mouth.y - nose.y + 0.01);\n" +
"                    if (fract(dy * 5.0) < 0.1) line = 0.2;\n" +
"                    col -= line;\n" +
"                    return vec4(col, 0.85);\n" +
"                }\n" +
"                return vec4(0.0);\n" +
"            }\n" +
"\n" +
"            void main() {\n" +
"                vec4 src = texture2D(sTexture, vTexCoord);\n" +
"                vec3 result = src.rgb;\n" +
"                float alpha = 1.0;\n" +
"\n" +
"                for (int i=0; i<3; i++) {\n" +
"                    if (i >= uFaceCount) break;\n" +
"                    Face face = uFaces[i];\n" +
"                    vec4 filterCol = vec4(0.0);\n" +
"\n" +
"                    if (uFilterType == 0) {\n" +
"                        filterCol = drawSunglasses(vTexCoord, face);\n" +
"                    } else if (uFilterType == 2) {\n" +
"                        filterCol = drawHat(vTexCoord, face);\n" +
"                    } else if (uFilterType == 4) {\n" +
"                        filterCol = drawMask(vTexCoord, face);\n" +
"                    } else {\n" +
"                        // For other types, use procedural fallback as sunglasses for now\n" +
"                        // Or sample filter texture if available\n" +
"                        if (uHasFilterTex == 1) {\n" +
"                            // Compute UV for filter texture based on face\n" +
"                            // Map face rect to filter texture\n" +
"                            vec4 rect = face.rect;\n" +
"                            vec2 localUv = (vTexCoord - rect.xy) / rect.zw;\n" +
"                            // Adjust for filter type positioning\n" +
"                            if (uFilterType == 1) { // glasses at eyes\n" +
"                                localUv = (vTexCoord - vec2(face.leftEye.x - face.eyeDist*0.5, face.leftEye.y - face.eyeDist*0.3)) / vec2(face.eyeDist*2.0, face.eyeDist*1.0);\n" +
"                            } else if (uFilterType == 7) { // mustache at mouth\n" +
"                                vec2 mustachePos = face.mouth;\n" +
"                                localUv = (vTexCoord - vec2(mustachePos.x - face.eyeDist*0.5, mustachePos.y - face.eyeDist*0.1)) / vec2(face.eyeDist*1.0, face.eyeDist*0.5);\n" +
"                            }\n" +
"                            if (localUv.x >= 0.0 && localUv.x <= 1.0 && localUv.y >= 0.0 && localUv.y <= 1.0) {\n" +
"                                vec4 tex = texture2D(sFilter, localUv);\n" +
"                                filterCol = tex;\n" +
"                            }\n" +
"                        } else {\n" +
"                            filterCol = drawSunglasses(vTexCoord, face);\n" +
"                        }\n" +
"                    }\n" +
"\n" +
"                    if (filterCol.a > 0.01) {\n" +
"                        result = mix(result, filterCol.rgb, filterCol.a * uIntensity);\n" +
"                    }\n" +
"                }\n" +
"\n" +
"                gl_FragColor = vec4(result, src.a);\n" +
"            }\n"
    }
}

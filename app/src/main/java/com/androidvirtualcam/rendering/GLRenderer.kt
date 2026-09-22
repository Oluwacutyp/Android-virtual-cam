package com.androidvirtualcam.rendering

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Log
import android.util.LruCache
import com.androidvirtualcam.scene.FilterType
import com.androidvirtualcam.scene.Layer
import com.androidvirtualcam.scene.Scene
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * ManyCam-like free compositor for Android.
 * - Camera OES texture with filters: grayscale, sepia, invert, blur, chroma key, vignette, pixelate, edge, beauty, background blur
 * - Image, Video (placeholder), Text, Color, Web (placeholder), Slideshow (placeholder)
 * - GPU accelerated, zero-copy for camera, texture cache for images/text
 * - Writes frames for VirtualCameraService (system-wide injection)
 */
class GLRenderer(private val context: Context) {

    private val tag = "GLRenderer"

    private val quadCoords = floatArrayOf(
        -1f, -1f, 0f, 0f, 0f,
         1f, -1f, 0f, 1f, 0f,
        -1f,  1f, 0f, 0f, 1f,
         1f,  1f, 0f, 1f, 1f
    )
    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(quadCoords.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(quadCoords); position(0) }

    private var oesProgram: ShaderProgram? = null
    private var tex2DProgram: ShaderProgram? = null
    private var colorProgram: ShaderProgram? = null

    // OES handles
    private var oesPosHandle = -1
    private var oesTexHandle = -1
    private var oesMvpHandle = -1
    private var oesTexMatrixHandle = -1
    private var oesTexSamplerHandle = -1
    private var oesAlphaHandle = -1
    private var oesFilterHandle = -1
    private var oesBrightnessHandle = -1
    private var oesContrastHandle = -1
    private var oesSaturationHandle = -1
    private var oesBlurHandle = -1
    private var oesVignetteHandle = -1
    private var oesKeyColorHandle = -1
    private var oesChromaThreshHandle = -1
    private var oesChromaSlopeHandle = -1
    private var oesTexSizeHandle = -1

    // 2D handles
    private var tex2DPosHandle = -1
    private var tex2DTexHandle = -1
    private var tex2DMvpHandle = -1
    private var tex2DTexMatrixHandle = -1
    private var tex2DSamplerHandle = -1
    private var tex2DAlphaHandle = -1
    private var tex2DBrightnessHandle = -1
    private var tex2DContrastHandle = -1
    private var tex2DSaturationHandle = -1
    private var tex2DBlurHandle = -1
    private var tex2DVignetteHandle = -1
    private var tex2DFilterHandle = -1
    private var tex2DTexSizeHandle = -1

    // Color handles
    private var colorPosHandle = -1
    private var colorMvpHandle = -1
    private var colorColorHandle = -1
    private var colorAlphaHandle = -1

    private val mvpMatrix = FloatArray(16)
    private val texMatrix = FloatArray(16)
    private val identityMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }

    private var cameraTexId: Int = -1
    private var cameraTextureMatrix = FloatArray(16)

    private val textureCache = LruCache<String, Int>(40)
    private val bitmapCache = mutableMapOf<String, Bitmap>()

    var currentScene: Scene? = null
    var outputWidth: Int = 1280
    var outputHeight: Int = 720

    fun onSurfaceCreated() {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        cameraTexId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        oesProgram = ShaderProgram(ShaderProgram.VERTEX_SHADER, ShaderProgram.FRAGMENT_SHADER_OES).apply {
            if (!compile()) Log.e(tag, "OES compile failed")
            else {
                use()
                oesPosHandle = getAttribLocation("aPosition")
                oesTexHandle = getAttribLocation("aTexCoord")
                oesMvpHandle = getUniformLocation("uMVPMatrix")
                oesTexMatrixHandle = getUniformLocation("uTexMatrix")
                oesTexSamplerHandle = getUniformLocation("sTexture")
                oesAlphaHandle = getUniformLocation("uAlpha")
                oesFilterHandle = getUniformLocation("uFilter")
                oesBrightnessHandle = getUniformLocation("uBrightness")
                oesContrastHandle = getUniformLocation("uContrast")
                oesSaturationHandle = getUniformLocation("uSaturation")
                oesBlurHandle = getUniformLocation("uBlurRadius")
                oesVignetteHandle = getUniformLocation("uVignette")
                oesKeyColorHandle = getUniformLocation("uKeyColor")
                oesChromaThreshHandle = getUniformLocation("uChromaThreshold")
                oesChromaSlopeHandle = getUniformLocation("uChromaSlope")
                oesTexSizeHandle = getUniformLocation("uTexSize")
            }
        }

        tex2DProgram = ShaderProgram(ShaderProgram.VERTEX_SHADER, ShaderProgram.FRAGMENT_SHADER_2D).apply {
            if (!compile()) Log.e(tag, "2D compile failed")
            else {
                use()
                tex2DPosHandle = getAttribLocation("aPosition")
                tex2DTexHandle = getAttribLocation("aTexCoord")
                tex2DMvpHandle = getUniformLocation("uMVPMatrix")
                tex2DTexMatrixHandle = getUniformLocation("uTexMatrix")
                tex2DSamplerHandle = getUniformLocation("sTexture")
                tex2DAlphaHandle = getUniformLocation("uAlpha")
                tex2DBrightnessHandle = getUniformLocation("uBrightness")
                tex2DContrastHandle = getUniformLocation("uContrast")
                tex2DSaturationHandle = getUniformLocation("uSaturation")
                tex2DBlurHandle = getUniformLocation("uBlurRadius")
                tex2DVignetteHandle = getUniformLocation("uVignette")
                tex2DFilterHandle = getUniformLocation("uFilter")
                tex2DTexSizeHandle = getUniformLocation("uTexSize")
            }
        }

        colorProgram = ShaderProgram(ShaderProgram.VERTEX_SHADER, ShaderProgram.FRAGMENT_SHADER_COLOR).apply {
            if (!compile()) Log.e(tag, "Color compile failed")
            else {
                use()
                colorPosHandle = getAttribLocation("aPosition")
                colorMvpHandle = getUniformLocation("uMVPMatrix")
                colorColorHandle = getUniformLocation("uColor")
                colorAlphaHandle = getUniformLocation("uAlpha")
            }
        }

        Matrix.setIdentityM(cameraTextureMatrix, 0)
        Log.i(tag, "GL created texId=$cameraTexId")
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        outputWidth = width
        outputHeight = height
        GLES20.glViewport(0, 0, width, height)
    }

    fun getCameraTextureId(): Int = cameraTexId
    fun updateCameraTextureMatrix(matrix: FloatArray) { cameraTextureMatrix = matrix }

    fun onDrawFrame() {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val scene = currentScene ?: return
        for (layer in scene.sortedLayers()) {
            try { drawLayer(layer) } catch (e: Exception) { Log.e(tag, "draw failed ${layer.id}", e) }
        }
    }

    private fun drawLayer(layer: Layer) {
        computeMvp(layer)
        when (layer) {
            is Layer.CameraLayer -> drawCameraLayer(layer)
            is Layer.ImageLayer -> drawImageLayer(layer)
            is Layer.VideoLayer -> drawVideoLayer(layer)
            is Layer.SlideshowLayer -> drawSlideshowLayer(layer)
            is Layer.TextLayer -> drawTextLayer(layer)
            is Layer.ColorLayer -> drawColorLayer(layer)
            is Layer.WebLayer -> drawWebLayer(layer)
        }
    }

    private fun computeMvp(layer: Layer) {
        val t = layer.transform
        Matrix.setIdentityM(mvpMatrix, 0)
        val tx = (t.x * 2f - 1f)
        val ty = -(t.y * 2f - 1f)
        Matrix.translateM(mvpMatrix, 0, tx, ty, 0f)
        Matrix.rotateM(mvpMatrix, 0, t.rotationDegrees, 0f, 0f, 1f)
        Matrix.scaleM(mvpMatrix, 0, t.scaleX, t.scaleY, 1f)
        if (layerNeedsBaseScale(layer)) {
            Matrix.scaleM(mvpMatrix, 0, 0.3f, 0.3f * (outputWidth.toFloat() / outputHeight.toFloat()), 1f)
        }
        Matrix.setIdentityM(texMatrix, 0)
        if (layer is Layer.CameraLayer) System.arraycopy(cameraTextureMatrix, 0, texMatrix, 0, 16)
    }

    private fun layerNeedsBaseScale(layer: Layer): Boolean = layer !is Layer.CameraLayer && layer !is Layer.ColorLayer

    private fun drawCameraLayer(layer: Layer.CameraLayer) {
        val program = oesProgram ?: return
        program.use()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexId)
        GLES20.glUniform1i(oesTexSamplerHandle, 0)
        GLES20.glUniformMatrix4fv(oesMvpHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(oesTexMatrixHandle, 1, false, texMatrix, 0)
        GLES20.glUniform1f(oesAlphaHandle, layer.transform.alpha * layer.effects.opacity)
        GLES20.glUniform1i(oesFilterHandle, mapFilter(layer.filter))
        GLES20.glUniform1f(oesBrightnessHandle, layer.effects.brightness)
        GLES20.glUniform1f(oesContrastHandle, layer.effects.contrast)
        GLES20.glUniform1f(oesSaturationHandle, layer.effects.saturation)
        GLES20.glUniform1f(oesBlurHandle, if (layer.effects.blurRadius > 0) layer.effects.blurRadius else if (layer.filter == FilterType.BLUR || layer.filter == FilterType.BACKGROUND_BLUR) 15f else 0f)
        GLES20.glUniform1f(oesVignetteHandle, layer.effects.vignetteStrength)
        GLES20.glUniform2f(oesTexSizeHandle, outputWidth.toFloat(), outputHeight.toFloat())

        // Chroma key
        if (layer.chromaKey != null && layer.chromaKey.isEnabled) {
            val c = layer.chromaKey.keyColor
            val r = ((c shr 16) and 0xFF) / 255f
            val g = ((c shr 8) and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            GLES20.glUniform3f(oesKeyColorHandle, r, g, b)
            GLES20.glUniform1f(oesChromaThreshHandle, layer.chromaKey.threshold)
            GLES20.glUniform1f(oesChromaSlopeHandle, layer.chromaKey.slope)
        } else {
            GLES20.glUniform3f(oesKeyColorHandle, 0f, 1f, 0f)
            GLES20.glUniform1f(oesChromaThreshHandle, 0.4f)
            GLES20.glUniform1f(oesChromaSlopeHandle, 0.1f)
        }

        bindQuad(oesPosHandle, oesTexHandle)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        unbindQuad(oesPosHandle, oesTexHandle)
    }

    private fun mapFilter(filter: FilterType): Int = when (filter) {
        FilterType.NONE -> 0
        FilterType.GRAYSCALE -> 1
        FilterType.SEPIA -> 2
        FilterType.INVERT -> 3
        FilterType.BLUR -> 4
        FilterType.CHROMA_KEY -> 5
        FilterType.VIGNETTE -> 6
        FilterType.PIXELATE -> 7
        FilterType.EDGE_DETECT -> 8
        FilterType.BEAUTY -> 9
        FilterType.BACKGROUND_BLUR -> 10
        FilterType.BRIGHTNESS -> 0
    }

    private fun drawImageLayer(layer: Layer.ImageLayer) {
        val texId = getOrLoadImageTexture(layer) ?: return
        draw2DTexture(texId, layer.transform.alpha * layer.effects.opacity, layer.effects)
    }

    private fun drawVideoLayer(layer: Layer.VideoLayer) {
        // For MVP, treat as image placeholder with "VIDEO" label
        val texId = getOrLoadVideoTexture(layer) ?: return
        draw2DTexture(texId, layer.transform.alpha * layer.effects.opacity, layer.effects)
    }

    private fun drawSlideshowLayer(layer: Layer.SlideshowLayer) {
        val texId = getOrLoadSlideshowTexture(layer) ?: return
        draw2DTexture(texId, layer.transform.alpha * layer.effects.opacity, layer.effects)
    }

    private fun drawWebLayer(layer: Layer.WebLayer) {
        val texId = getOrLoadWebTexture(layer) ?: return
        draw2DTexture(texId, layer.transform.alpha * layer.effects.opacity, layer.effects)
    }

    private fun drawTextLayer(layer: Layer.TextLayer) {
        val texId = getOrLoadTextTexture(layer) ?: return
        draw2DTexture(texId, layer.transform.alpha * layer.effects.opacity, layer.effects)
    }

    private fun draw2DTexture(texId: Int, alpha: Float, effects: com.androidvirtualcam.scene.LayerEffects = com.androidvirtualcam.scene.LayerEffects()) {
        val program = tex2DProgram ?: return
        program.use()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glUniform1i(tex2DSamplerHandle, 0)
        GLES20.glUniformMatrix4fv(tex2DMvpHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(tex2DTexMatrixHandle, 1, false, identityMatrix, 0)
        GLES20.glUniform1f(tex2DAlphaHandle, alpha)
        GLES20.glUniform1f(tex2DBrightnessHandle, effects.brightness)
        GLES20.glUniform1f(tex2DContrastHandle, effects.contrast)
        GLES20.glUniform1f(tex2DSaturationHandle, effects.saturation)
        GLES20.glUniform1f(tex2DBlurHandle, effects.blurRadius)
        GLES20.glUniform1f(tex2DVignetteHandle, effects.vignetteStrength)
        GLES20.glUniform1i(tex2DFilterHandle, 0)
        GLES20.glUniform2f(tex2DTexSizeHandle, outputWidth.toFloat(), outputHeight.toFloat())

        bindQuad(tex2DPosHandle, tex2DTexHandle)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        unbindQuad(tex2DPosHandle, tex2DTexHandle)
    }

    private fun drawColorLayer(layer: Layer.ColorLayer) {
        val program = colorProgram ?: return
        program.use()
        GLES20.glUniformMatrix4fv(colorMvpHandle, 1, false, mvpMatrix, 0)
        val color = layer.color
        val a = ((color shr 24) and 0xFF) / 255f
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        GLES20.glUniform4f(colorColorHandle, r, g, b, a)
        GLES20.glUniform1f(colorAlphaHandle, layer.transform.alpha * layer.effects.opacity)
        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(colorPosHandle)
        GLES20.glVertexAttribPointer(colorPosHandle, 3, GLES20.GL_FLOAT, false, 5 * 4, vertexBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(colorPosHandle)
    }

    private fun bindQuad(posHandle: Int, texHandle: Int) {
        if (posHandle < 0 || texHandle < 0) return
        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 5 * 4, vertexBuffer)
        vertexBuffer.position(3)
        GLES20.glEnableVertexAttribArray(texHandle)
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 5 * 4, vertexBuffer)
    }

    private fun unbindQuad(posHandle: Int, texHandle: Int) {
        if (posHandle >= 0) GLES20.glDisableVertexAttribArray(posHandle)
        if (texHandle >= 0) GLES20.glDisableVertexAttribArray(texHandle)
    }

    private fun getOrLoadImageTexture(layer: Layer.ImageLayer): Int? {
        val key = "img_${layer.id}_${layer.imageUri}_${layer.imageResName}"
        textureCache.get(key)?.let { return it }
        val bitmap = try { createPlaceholderBitmap(layer.name.take(4).uppercase(), 512, 512, 0xFF3DDC84) } catch (e: Exception) { createPlaceholderBitmap("?", 256, 256, 0xFF888888) }
        val texId = uploadBitmap(bitmap, key)
        bitmapCache[key] = bitmap
        return texId
    }

    private fun getOrLoadVideoTexture(layer: Layer.VideoLayer): Int? {
        val key = "vid_${layer.id}_${layer.videoUri}"
        textureCache.get(key)?.let { return it }
        val bitmap = createPlaceholderBitmap("VIDEO", 640, 360, 0xFF6200EE)
        val texId = uploadBitmap(bitmap, key)
        bitmapCache[key] = bitmap
        return texId
    }

    private fun getOrLoadSlideshowTexture(layer: Layer.SlideshowLayer): Int? {
        val key = "slide_${layer.id}_${layer.imageUris.hashCode()}"
        textureCache.get(key)?.let { return it }
        val bitmap = createPlaceholderBitmap("SLIDE", 640, 360, 0xFFFF9800)
        val texId = uploadBitmap(bitmap, key)
        bitmapCache[key] = bitmap
        return texId
    }

    private fun getOrLoadWebTexture(layer: Layer.WebLayer): Int? {
        val key = "web_${layer.id}_${layer.url.hashCode()}"
        textureCache.get(key)?.let { return it }
        val bitmap = createPlaceholderBitmap("WEB", 640, 360, 0xFF03A9F4)
        val texId = uploadBitmap(bitmap, key)
        bitmapCache[key] = bitmap
        return texId
    }

    private fun getOrLoadTextTexture(layer: Layer.TextLayer): Int? {
        val key = "txt_${layer.id}_${layer.text.hashCode()}_${layer.textColor}_${layer.fontSizeSp}_${layer.isBold}_${layer.style}"
        textureCache.get(key)?.let { return it }
        val bitmap = createTextBitmap(layer) ?: return null
        val texId = uploadBitmap(bitmap, key)
        bitmapCache[key] = bitmap
        return texId
    }

    private fun createTextBitmap(layer: Layer.TextLayer): Bitmap? {
        return try {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = layer.textColor.toInt()
                textSize = layer.fontSizeSp * 3f
                isFakeBoldText = layer.isBold
            }
            val bgColor = layer.backgroundColor.toInt()
            val textWidth = paint.measureText(layer.text)
            val fm = paint.fontMetrics
            val textHeight = fm.descent - fm.ascent
            val width = (textWidth + 60).toInt().coerceAtLeast(128)
            val height = (textHeight + 60).toInt().coerceAtLeast(64)

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            // Lower third style
            if (layer.style == com.androidvirtualcam.scene.TextStyle.LOWER_THIRD && layer.lowerThird != null) {
                val lt = layer.lowerThird
                val accentPaint = Paint().apply { color = lt.accentColor.toInt() }
                canvas.drawRect(0f, 0f, 20f, height.toFloat(), accentPaint)
                val bgPaint = Paint().apply { color = 0xCC000000.toInt() }
                canvas.drawRect(20f, 0f, width.toFloat(), height.toFloat(), bgPaint)
                paint.color = android.graphics.Color.WHITE
                paint.textSize = 32f * 3f / 2
                canvas.drawText(lt.title, 40f, -fm.ascent + 20f, paint)
                paint.textSize = 20f * 3f / 2
                paint.color = 0xFFCCCCCC.toInt()
                canvas.drawText(lt.subtitle, 40f, -fm.ascent + 60f, paint)
            } else {
                if ((layer.backgroundColor shr 24) != 0L) canvas.drawColor(bgColor)
                canvas.drawText(layer.text, 30f, -fm.ascent + 30f, paint)
            }
            bitmap
        } catch (e: Exception) { Log.e(tag, "createTextBitmap failed", e); null }
    }

    private fun createPlaceholderBitmap(text: String, w: Int, h: Int, color: Long): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { this.color = color.toInt(); style = Paint.Style.FILL }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0xFFFFFFFF.toInt(); textSize = 48f; textAlign = Paint.Align.CENTER }
        canvas.drawText(text, w / 2f, h / 2f, textPaint)
        return bitmap
    }

    private fun uploadBitmap(bitmap: Bitmap, cacheKey: String): Int {
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        val texId = texIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        textureCache.put(cacheKey, texId)
        return texId
    }

    fun release() {
        oesProgram?.release()
        tex2DProgram?.release()
        colorProgram?.release()
        val allTex = mutableListOf<Int>()
        // Fix keyAt – android.util.LruCache has no keyAt, use snapshot() HashMap
        for ((_, value) in textureCache.snapshot()) {
            allTex.add(value)
        }
        if (allTex.isNotEmpty()) GLES20.glDeleteTextures(allTex.size, allTex.toIntArray(), 0)
        textureCache.evictAll()
        if (cameraTexId != -1) GLES20.glDeleteTextures(1, intArrayOf(cameraTexId), 0)
        cameraTexId = -1
        bitmapCache.values.forEach { if (!it.isRecycled) it.recycle() }
        bitmapCache.clear()
    }
}

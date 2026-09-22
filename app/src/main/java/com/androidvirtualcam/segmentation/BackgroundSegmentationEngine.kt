package com.androidvirtualcam.segmentation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import com.androidvirtualcam.compositor.GLFramebuffer
import com.androidvirtualcam.compositor.GLTexture
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * BackgroundSegmentationEngine – MediaPipe Selfie Segmentation with GPU delegate at 30fps,
 * outputs alpha mask as GL texture directly (no CPU readback of mask texture),
 * temporal filtering 0.7/0.3 blend with previous frame, fallback to ML Kit Subject Segmentation.
 *
 * Works without green screen on both front and rear cameras.
 *
 * Background options: solid color, image, video, gaussian blur of original, custom GL texture.
 */
class BackgroundSegmentationEngine(
    private val context: Context
) {
    companion object {
        private const val TAG = "BgSegEngine"
        private const val MODEL_ASSET = "selfie_segmenter.tflite"
        private const val MODEL_MULTICLASS_ASSET = "selfie_multiclass_256x256.tflite"
        private const val TARGET_FPS = 30
        private const val FRAME_INTERVAL_MS = 1000L / TARGET_FPS
        private const val TEMPORAL_BLEND_CURRENT = 0.7f
        private const val TEMPORAL_BLEND_PREVIOUS = 0.3f

        private const val VERTEX_SHADER = """
            #version 100
            precision mediump float;
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }
        """

        private const val FRAGMENT_SHADER_MASK_UPLOAD = """
            #version 100
            precision mediump float;
            varying vec2 v_TexCoord;
            
            uniform sampler2D uMaskTexture;
            void main() {
                float mask = texture2D(uMaskTexture, v_TexCoord).r;
                gl_FragColor = vec4(mask, mask, mask, 1.0);
            }
        """

        private const val FRAGMENT_SHADER_TEMPORAL_BLEND = """
            #version 100
            precision mediump float;
            varying vec2 v_TexCoord;
            
            uniform sampler2D uCurrentMask;
            uniform sampler2D uPreviousMask;
            uniform float uBlendCurrent;
            uniform float uBlendPrevious;
            void main() {
                float current = texture2D(uCurrentMask, v_TexCoord).r;
                float previous = texture2D(uPreviousMask, v_TexCoord).r;
                float filtered = current * uBlendCurrent + previous * uBlendPrevious;
                filtered = clamp(filtered, 0.0, 1.0);
                gl_FragColor = vec4(filtered, filtered, filtered, 1.0);
            }
        """

        private const val FRAGMENT_SHADER_BLUR = """
            #version 100
            precision mediump float;
            varying vec2 v_TexCoord;
            
            uniform sampler2D uTexture;
            uniform vec2 uTexelSize;
            uniform float uBlurRadius;
            void main() {
                vec4 sum = vec4(0.0);
                float radius = uBlurRadius;
                vec2 texel = uTexelSize * radius;
                sum += texture2D(uTexture, v_TexCoord + vec2(-texel.x, -texel.y)) * 0.0625;
                sum += texture2D(uTexture, v_TexCoord + vec2(0.0, -texel.y)) * 0.125;
                sum += texture2D(uTexture, v_TexCoord + vec2(texel.x, -texel.y)) * 0.0625;
                sum += texture2D(uTexture, v_TexCoord + vec2(-texel.x, 0.0)) * 0.125;
                sum += texture2D(uTexture, v_TexCoord) * 0.25;
                sum += texture2D(uTexture, v_TexCoord + vec2(texel.x, 0.0)) * 0.125;
                sum += texture2D(uTexture, v_TexCoord + vec2(-texel.x, texel.y)) * 0.0625;
                sum += texture2D(uTexture, v_TexCoord + vec2(0.0, texel.y)) * 0.125;
                sum += texture2D(uTexture, v_TexCoord + vec2(texel.x, texel.y)) * 0.0625;
                gl_FragColor = sum;
            }
        """

        private const val FRAGMENT_SHADER_SOLID = """
            #version 100
            precision mediump float;
            
            uniform vec4 uColor;
            void main() {
                gl_FragColor = uColor;
            }
        """
    }

    sealed class BackgroundType {
        data class SolidColor(val color: Int) : BackgroundType()
        data class Image(val bitmap: Bitmap) : BackgroundType()
        data class Video(val textureProvider: () -> GLTexture?) : BackgroundType()
        data class Blur(val radius: Float = 15f) : BackgroundType()
        data class CustomTexture(val texture: GLTexture) : BackgroundType()
    }

    private var imageSegmenter: ImageSegmenter? = null
    private var mlKitSegmenter: com.google.mlkit.vision.segmentation.subject.SubjectSegmenter? = null
    private var useMlKitFallback = false

    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null
    private var eglConfig: EGLConfig? = null
    private var sharedEglContext: EGLContext = EGL14.EGL_NO_CONTEXT

    private var programMaskUpload = 0
    private var programTemporalBlend = 0
    private var programBlur = 0
    private var programSolid = 0

    private var maskTextureId = 0
    private var prevMaskTextureId = 0
    private var filteredMaskTextureId = 0
    private var uploadTextureId = 0

    private var maskFramebuffer: GLFramebuffer? = null
    private var prevMaskFramebuffer: GLFramebuffer? = null
    private var filteredMaskFramebuffer: GLFramebuffer? = null
    private var blurFramebuffer: GLFramebuffer? = null

    private var vertexBuffer: FloatBuffer? = null
    private var texCoordBuffer: FloatBuffer? = null

    private var width = 0
    private var height = 0
    private var lastSegmentationTime = 0L
    private var isInitialized = false
    private val isProcessing = AtomicBoolean(false)

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var currentMaskBitmap: Bitmap? = null
    private var backgroundType: BackgroundType = BackgroundType.Blur(15f)
    private var backgroundImageTexture: GLTexture? = null

    fun setSharedEglContext(eglContext: EGLContext, eglDisplay: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)) {
        sharedEglContext = eglContext
        this.eglDisplay = eglDisplay
    }

    fun setBackgroundType(type: BackgroundType) {
        backgroundType = type
        if (type is BackgroundType.Image) {
            backgroundImageTexture?.release()
            backgroundImageTexture = null
        }
    }

    fun initialize(w: Int, h: Int): Boolean {
        if (isInitialized && width == w && height == h) return true
        width = w
        height = h

        return try {
            initMediaPipe()
            initEGL()
            initGL()
            isInitialized = true
            Log.i(TAG, "BackgroundSegmentationEngine initialized ${w}x${h} GPU delegate, fallback ML Kit ready")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaPipe, falling back to ML Kit", e)
            useMlKitFallback = true
            try {
                initMlKit()
                initEGL()
                initGL()
                isInitialized = true
                Log.i(TAG, "BackgroundSegmentationEngine initialized with ML Kit fallback ${w}x${h}")
                true
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to initialize ML Kit fallback", e2)
                false
            }
        }
    }

    private fun initMediaPipe() {
        try {
            val modelAsset = try {
                context.assets.open(MODEL_MULTICLASS_ASSET).close()
                MODEL_MULTICLASS_ASSET
            } catch (_: Exception) {
                try {
                    context.assets.open(MODEL_ASSET).close()
                    MODEL_ASSET
                } catch (_: Exception) {
                    Log.w(TAG, "No segmentation model in assets, will use ML Kit fallback")
                    throw RuntimeException("Model not found in assets")
                }
            }

            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(modelAsset)
                .setDelegate(Delegate.GPU)
                .build()

            val options = ImageSegmenter.ImageSegmenterOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setOutputConfidenceMasks(true)
                .setOutputCategoryMask(false)
                .setResultListener { result, _ ->
                    handleMediaPipeResult(result)
                }
                .setErrorListener { error ->
                    Log.e(TAG, "MediaPipe error: ${error.message}", error)
                    if (!useMlKitFallback) {
                        Log.w(TAG, "Switching to ML Kit fallback due to MediaPipe error")
                        useMlKitFallback = true
                        try {
                            initMlKit()
                        } catch (e: Exception) {
                            Log.e(TAG, "ML Kit fallback init failed", e)
                        }
                    }
                }
                .build()

            imageSegmenter = ImageSegmenter.createFromOptions(context, options)
            Log.i(TAG, "MediaPipe ImageSegmenter created with GPU delegate, model $modelAsset")
        } catch (e: Exception) {
            Log.e(TAG, "MediaPipe init failed, will fallback to ML Kit", e)
            throw e
        }
    }

    private fun initMlKit() {
        val options = SubjectSegmenterOptions.Builder()
            .enableForegroundBitmap()
            .enableForegroundConfidenceMask()
            .enableMultipleSubjects(
                SubjectSegmenterOptions.SubjectResultOptions.Builder()
                    .enableConfidenceMask()
                    .enableSubjectBitmap()
                    .build()
            )
            .build()
        mlKitSegmenter = SubjectSegmentation.getClient(options)
        Log.i(TAG, "ML Kit SubjectSegmentation client created")
    }

    private fun initEGL() {
        val display = eglDisplay ?: EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        eglDisplay = display
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            throw RuntimeException("eglInitialize failed")
        }

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(display, attribList, 0, configs, 0, 1, numConfigs, 0)
        val config = configs[0] ?: throw RuntimeException("No EGL config")
        eglConfig = config

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        val share = if (sharedEglContext != EGL14.EGL_NO_CONTEXT) sharedEglContext else EGL14.EGL_NO_CONTEXT
        eglContext = EGL14.eglCreateContext(display, config, share, ctxAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) throw RuntimeException("eglCreateContext failed")

        val pbufferAttribs = intArrayOf(
            EGL14.EGL_WIDTH, width,
            EGL14.EGL_HEIGHT, height,
            EGL14.EGL_NONE
        )
        eglSurface = EGL14.eglCreatePbufferSurface(display, config, pbufferAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) throw RuntimeException("eglCreatePbufferSurface failed")

        EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)
    }

    private fun initGL() {
        programMaskUpload = createProgram(VERTEX_SHADER, FRAGMENT_SHADER_MASK_UPLOAD)
        programTemporalBlend = createProgram(VERTEX_SHADER, FRAGMENT_SHADER_TEMPORAL_BLEND)
        programBlur = createProgram(VERTEX_SHADER, FRAGMENT_SHADER_BLUR)
        programSolid = createProgram(VERTEX_SHADER, FRAGMENT_SHADER_SOLID)

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

        val texIds = IntArray(4)
        GLES20.glGenTextures(4, texIds, 0)
        uploadTextureId = texIds[0]
        maskTextureId = texIds[1]
        prevMaskTextureId = texIds[2]
        filteredMaskTextureId = texIds[3]

        for (texId in texIds) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_R8, width, height, 0, GLES30.GL_RED, GLES20.GL_UNSIGNED_BYTE, null)
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        maskFramebuffer = createFramebufferWithTexture(maskTextureId)
        prevMaskFramebuffer = createFramebufferWithTexture(prevMaskTextureId)
        filteredMaskFramebuffer = createFramebufferWithTexture(filteredMaskTextureId)
        blurFramebuffer = GLFramebuffer.create(width, height)
    }

    private fun createFramebufferWithTexture(texId: Int): GLFramebuffer {
        val fboIds = IntArray(1)
        GLES20.glGenFramebuffers(1, fboIds, 0)
        val fboId = fboIds[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texId, 0)
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw RuntimeException("Framebuffer incomplete: $status")
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        val texture = GLTexture.wrapExisting(texId, GLES20.GL_TEXTURE_2D, width, height, owned = false)
        return GLFramebuffer(fboId, texture, width, height)
    }

    private fun createProgram(vs: String, fs: String): Int {
        val vsId = compileShader(GLES20.GL_VERTEX_SHADER, vs)
        val fsId = compileShader(GLES20.GL_FRAGMENT_SHADER, fs)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vsId)
        GLES20.glAttachShader(program, fsId)
        GLES20.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("Program link failed: $log")
        }
        GLES20.glDeleteShader(vsId)
        GLES20.glDeleteShader(fsId)
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Shader compile failed: $log")
        }
        return shader
    }

    private fun handleMediaPipeResult(result: com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenterResult) {
        try {
            val confidenceMasksOpt = result.confidenceMasks()
            if (!confidenceMasksOpt.isPresent) return
            val confidenceMasks = confidenceMasksOpt.get()
            if (confidenceMasks.isEmpty()) return
            val mask = confidenceMasks[0]
            val byteBuffer = com.google.mediapipe.framework.image.ByteBufferExtractor.extract(mask)
            byteBuffer.rewind()
            val floatBuffer = byteBuffer.asFloatBuffer()
            val maskWidth = try { mask.width } catch (_: Exception) { try { mask.javaClass.getMethod("getWidth").invoke(mask) as Int } catch (_: Exception) { width } }
            val maskHeight = try { mask.height } catch (_: Exception) { try { mask.javaClass.getMethod("getHeight").invoke(mask) as Int } catch (_: Exception) { height } }
            val maskData = ByteArray(maskWidth * maskHeight)
            for (i in 0 until maskWidth * maskHeight) {
                val confidence = if (floatBuffer.hasRemaining()) floatBuffer.get() else 0f
                maskData[i] = (confidence * 255f).toInt().coerceIn(0, 255).toByte()
            }
            scope.launch(Dispatchers.Main) {
                uploadMaskToGL(maskData, maskWidth, maskHeight)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle MediaPipe result", e)
        }
    }

    fun processBitmap(bitmap: Bitmap, timestampMs: Long): Boolean {
        if (!isInitialized) return false
        val now = System.currentTimeMillis()
        if (now - lastSegmentationTime < FRAME_INTERVAL_MS) {
            return false
        }
        lastSegmentationTime = now

        if (isProcessing.getAndSet(true)) return false

        try {
            if (useMlKitFallback) {
                processWithMlKit(bitmap)
            } else {
                processWithMediaPipe(bitmap, timestampMs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Segmentation failed, trying ML Kit fallback", e)
            if (!useMlKitFallback) {
                useMlKitFallback = true
                try {
                    initMlKit()
                    processWithMlKit(bitmap)
                } catch (e2: Exception) {
                    Log.e(TAG, "ML Kit fallback also failed", e2)
                }
            }
        } finally {
            isProcessing.set(false)
        }
        return true
    }

    private fun processWithMediaPipe(bitmap: Bitmap, timestampMs: Long) {
        val segmenter = imageSegmenter ?: throw RuntimeException("MediaPipe segmenter not initialized")
        val mpImage = BitmapImageBuilder(bitmap).build()
        segmenter.segmentAsync(mpImage, timestampMs)
    }

    private fun processWithMlKit(bitmap: Bitmap) {
        val segmenter = mlKitSegmenter ?: throw RuntimeException("ML Kit segmenter not initialized")
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        segmenter.process(inputImage)
            .addOnSuccessListener { result ->
                try {
                    val foregroundMask = result.foregroundConfidenceMask
                    if (foregroundMask != null) {
                        foregroundMask.rewind()
                        val maskWidth = result.foregroundBitmap?.width ?: bitmap.width
                        val maskHeight = result.foregroundBitmap?.height ?: bitmap.height
                        val maskData = ByteArray(maskWidth * maskHeight)
                        for (i in 0 until maskWidth * maskHeight) {
                            val confidence = if (foregroundMask.hasRemaining()) foregroundMask.get() else 0f
                            maskData[i] = (confidence * 255f).toInt().coerceIn(0, 255).toByte()
                        }
                        scope.launch(Dispatchers.Main) {
                            uploadMaskToGL(maskData, maskWidth, maskHeight)
                        }
                    } else {
                        val subjects = result.subjects
                        if (subjects.isNotEmpty()) {
                            val subject = subjects[0]
                            val mask = subject.confidenceMask
                            mask?.rewind()
                            val maskWidth = subject.width
                            val maskHeight = subject.height
                            val maskData = ByteArray(maskWidth * maskHeight)
                            for (i in 0 until maskWidth * maskHeight) {
                                val confidence = if (mask != null && mask.hasRemaining()) mask.get() else 0f
                                maskData[i] = (confidence * 255f).toInt().coerceIn(0, 255).toByte()
                            }
                            scope.launch(Dispatchers.Main) {
                                uploadMaskToGL(maskData, maskWidth, maskHeight)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "ML Kit result handling failed", e)
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "ML Kit segmentation failed", e)
            }
    }

    private fun uploadMaskToGL(maskData: ByteArray, maskWidth: Int, maskHeight: Int) {
        try {
            val display = eglDisplay ?: return
            val surface = eglSurface ?: return
            val ctx = eglContext ?: return
            EGL14.eglMakeCurrent(display, surface, surface, ctx)

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, uploadTextureId)
            if (maskWidth == width && maskHeight == height) {
                GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height, GLES30.GL_RED, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(maskData))
            } else {
                val scaled = scaleMask(maskData, maskWidth, maskHeight, width, height)
                GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height, GLES30.GL_RED, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(scaled))
            }

            applyTemporalFiltering()

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        } catch (e: Exception) {
            Log.e(TAG, "uploadMaskToGL failed", e)
        }
    }

    private fun scaleMask(src: ByteArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): ByteArray {
        val dst = ByteArray(dstW * dstH)
        val xRatio = srcW.toFloat() / dstW
        val yRatio = srcH.toFloat() / dstH
        for (y in 0 until dstH) {
            for (x in 0 until dstW) {
                val srcX = (x * xRatio).toInt().coerceIn(0, srcW - 1)
                val srcY = (y * yRatio).toInt().coerceIn(0, srcH - 1)
                dst[y * dstW + x] = src[srcY * srcW + srcX]
            }
        }
        return dst
    }

    private fun applyTemporalFiltering() {
        try {
            // Step 1: blend current mask (uploadTexture) with previous mask (prevMask) into filtered mask
            filteredMaskFramebuffer?.bind()
            GLES20.glViewport(0, 0, width, height)
            GLES20.glUseProgram(programTemporalBlend)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, uploadTextureId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(programTemporalBlend, "uCurrentMask"), 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prevMaskTextureId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(programTemporalBlend, "uPreviousMask"), 1)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(programTemporalBlend, "uBlendCurrent"), TEMPORAL_BLEND_CURRENT)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(programTemporalBlend, "uBlendPrevious"), TEMPORAL_BLEND_PREVIOUS)

            val posLoc = GLES20.glGetAttribLocation(programTemporalBlend, "a_Position")
            val texLoc = GLES20.glGetAttribLocation(programTemporalBlend, "a_TexCoord")
            GLES20.glEnableVertexAttribArray(posLoc)
            GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
            GLES20.glEnableVertexAttribArray(texLoc)
            GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(posLoc)
            GLES20.glDisableVertexAttribArray(texLoc)
            filteredMaskFramebuffer?.unbind()

            // Step 2: copy filtered to mask texture and also to prev for next frame
            // Copy filtered -> mask
            GLES20.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, filteredMaskFramebuffer!!.fboId)
            GLES20.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, maskFramebuffer!!.fboId)
            GLES30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GLES20.GL_COLOR_BUFFER_BIT, GLES20.GL_NEAREST)
            // Copy filtered -> prev
            GLES20.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, filteredMaskFramebuffer!!.fboId)
            GLES20.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, prevMaskFramebuffer!!.fboId)
            GLES30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GLES20.GL_COLOR_BUFFER_BIT, GLES20.GL_NEAREST)
            GLES20.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0)
            GLES20.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)

            GLES20.glUseProgram(0)
        } catch (e: Exception) {
            Log.e(TAG, "Temporal filtering failed", e)
        }
    }

    fun getMaskTexture(): GLTexture {
        return GLTexture.wrapExisting(filteredMaskTextureId, GLES20.GL_TEXTURE_2D, width, height, owned = false)
    }

    fun getBackgroundTexture(foregroundTexture: GLTexture, customBackground: GLTexture? = null): GLTexture {
        val display = eglDisplay ?: return foregroundTexture
        val surface = eglSurface ?: return foregroundTexture
        val ctx = eglContext ?: return foregroundTexture

        try {
            EGL14.eglMakeCurrent(display, surface, surface, ctx)

            return when (val bg = backgroundType) {
                is BackgroundType.SolidColor -> {
                    blurFramebuffer?.bind()
                    GLES20.glViewport(0, 0, width, height)
                    GLES20.glUseProgram(programSolid)
                    val colorLoc = GLES20.glGetUniformLocation(programSolid, "uColor")
                    val r = Color.red(bg.color) / 255f
                    val g = Color.green(bg.color) / 255f
                    val b = Color.blue(bg.color) / 255f
                    val a = Color.alpha(bg.color) / 255f
                    GLES20.glUniform4f(colorLoc, r, g, b, a)
                    val posLoc = GLES20.glGetAttribLocation(programSolid, "a_Position")
                    val texLoc = GLES20.glGetAttribLocation(programSolid, "a_TexCoord")
                    GLES20.glEnableVertexAttribArray(posLoc)
                    GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
                    GLES20.glEnableVertexAttribArray(texLoc)
                    GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                    GLES20.glDisableVertexAttribArray(posLoc)
                    GLES20.glDisableVertexAttribArray(texLoc)
                    blurFramebuffer?.unbind()
                    GLTexture.wrapExisting(blurFramebuffer!!.texture.textureId, GLES20.GL_TEXTURE_2D, width, height, owned = false)
                }
                is BackgroundType.Image -> {
                    backgroundImageTexture?.let { tex ->
                        if (tex.width == width && tex.height == height) tex else foregroundTexture
                    } ?: run {
                        val bmp = bg.bitmap
                        val texId = createTextureFromBitmap(bmp)
                        backgroundImageTexture = GLTexture.wrapExisting(texId, GLES20.GL_TEXTURE_2D, bmp.width, bmp.height, owned = true)
                        backgroundImageTexture!!
                    }
                }
                is BackgroundType.Video -> {
                    bg.textureProvider()?.let { it } ?: foregroundTexture
                }
                is BackgroundType.Blur -> {
                    blurFramebuffer?.bind()
                    GLES20.glViewport(0, 0, width, height)
                    GLES20.glUseProgram(programBlur)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(foregroundTexture.target, foregroundTexture.textureId)
                    GLES20.glUniform1i(GLES20.glGetUniformLocation(programBlur, "uTexture"), 0)
                    GLES20.glUniform2f(GLES20.glGetUniformLocation(programBlur, "uTexelSize"), 1f / width, 1f / height)
                    GLES20.glUniform1f(GLES20.glGetUniformLocation(programBlur, "uBlurRadius"), bg.radius)
                    val posLoc = GLES20.glGetAttribLocation(programBlur, "a_Position")
                    val texLoc = GLES20.glGetAttribLocation(programBlur, "a_TexCoord")
                    GLES20.glEnableVertexAttribArray(posLoc)
                    GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
                    GLES20.glEnableVertexAttribArray(texLoc)
                    GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                    GLES20.glDisableVertexAttribArray(posLoc)
                    GLES20.glDisableVertexAttribArray(texLoc)
                    blurFramebuffer?.unbind()
                    GLTexture.wrapExisting(blurFramebuffer!!.texture.textureId, GLES20.GL_TEXTURE_2D, width, height, owned = false)
                }
                is BackgroundType.CustomTexture -> {
                    bg.texture
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getBackgroundTexture failed", e)
            return foregroundTexture
        }
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

    fun processGLTexture(inputTexture: GLTexture, timestampMs: Long): GLTexture {
        return getMaskTexture()
    }

    fun release() {
        try {
            imageSegmenter?.close()
        } catch (_: Exception) {}
        try {
            mlKitSegmenter?.close()
        } catch (_: Exception) {}

        try {
            eglDisplay?.let { display ->
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                eglSurface?.let { EGL14.eglDestroySurface(display, it) }
                eglContext?.let { EGL14.eglDestroyContext(display, it) }
            }
        } catch (_: Exception) {}

        try {
            if (programMaskUpload != 0) GLES20.glDeleteProgram(programMaskUpload)
            if (programTemporalBlend != 0) GLES20.glDeleteProgram(programTemporalBlend)
            if (programBlur != 0) GLES20.glDeleteProgram(programBlur)
            if (programSolid != 0) GLES20.glDeleteProgram(programSolid)
            val texIds = intArrayOf(maskTextureId, prevMaskTextureId, filteredMaskTextureId, uploadTextureId)
            GLES20.glDeleteTextures(4, texIds, 0)
        } catch (_: Exception) {}

        maskFramebuffer?.close()
        prevMaskFramebuffer?.close()
        filteredMaskFramebuffer?.close()
        blurFramebuffer?.close()
        backgroundImageTexture?.release()

        imageSegmenter = null
        mlKitSegmenter = null
        isInitialized = false
        Log.i(TAG, "BackgroundSegmentationEngine released")
    }
}

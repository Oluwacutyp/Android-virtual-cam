package com.androidvirtualcam.camera

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.androidvirtualcam.scene.CameraFacing
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.guava.await
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Pure CameraX PreviewView – ZERO GL in preview path.
 * No EGL, no GLSurfaceView, no custom shaders touching preview surface.
 * For recording/streaming: ImageAnalysis -> ByteArray (YUV) -> CPU processing -> MediaCodec encoder.
 */
class CameraManager(private val context: Context) {

    var cameraProvider: ProcessCameraProvider? = null
        private set
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var videoCapture: VideoCapture<Recorder>? = null

    var state: CameraState = CameraState()
        private set

    private val tag = "CameraManager"

    // Face detection listener for Center Stage
    private var faceDetectionListener: ((InputImage, Boolean) -> Unit)? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val cpuFrameExecutor = Executors.newSingleThreadExecutor()

    // CPU frame processor for recording/streaming – ByteArray (NV21) -> MediaCodec
    private var cpuFrameListener: ((ByteArray, Int, Int, Long) -> Unit)? = null
    private var lastCpuFrameTime = 0L

    fun setFaceDetectionListener(listener: ((InputImage, Boolean) -> Unit)?) {
        faceDetectionListener = listener
        Log.d(tag, "Face detection listener ${if (listener != null) "set" else "cleared"}")
    }

    fun setCpuFrameListener(listener: ((ByteArray, Int, Int, Long) -> Unit)?) {
        cpuFrameListener = listener
        Log.d(tag, "CPU frame listener ${if (listener != null) "set" else "cleared"} – for recording/streaming via ImageAnalysis ByteArray -> CPU -> MediaCodec")
    }

    suspend fun initProvider(): ProcessCameraProvider {
        val provider = ProcessCameraProvider.getInstance(context).await()
        cameraProvider = provider
        return provider
    }

    fun isProviderInitialized(): Boolean = cameraProvider != null

    /**
     * Pure preview – only PreviewView, no GL, no EGL, no shaders.
     * This is the ONLY path for display – cannot crash from shader compilation.
     */
    fun bindPreview(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        facing: CameraFacing = state.facing,
        executor: Executor = ContextCompat.getMainExecutor(context)
    ): Result<Unit> {
        return try {
            val provider = cameraProvider ?: run {
                Log.e(tag, "Provider not initialized")
                return Result.failure(IllegalStateException("CameraProvider not initialized"))
            }

            val selector = when (facing) {
                CameraFacing.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
                CameraFacing.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
            }

            if (!provider.hasCamera(selector)) {
                val msg = "Camera $facing not available"
                Log.w(tag, msg)
                state = state.copy(error = msg)
                return Result.failure(IllegalStateException(msg))
            }

            // Samsung S22 Ultra fix: Use MIC and start BEFORE camera
            try {
                val voiceEngine = com.androidvirtualcam.voice.VoiceChangerEngine.getInstance()
                if (voiceEngine != null && !voiceEngine.isActive.value) {
                    Log.i(tag, "Samsung S22 Ultra fix: Starting mic BEFORE camera with MIC source")
                    voiceEngine.start()
                }
            } catch (e: Exception) {
                Log.w(tag, "Failed to start mic before camera (Samsung fix)", e)
            }

            preview = Preview.Builder()
                .setTargetResolution(Size(1280, 720))
                .build().apply {
                    setSurfaceProvider(surfaceProvider)
                }

            // For recording/streaming: ImageAnalysis provides ByteArray frames -> CPU -> MediaCodec
            // No GL, no EGL, no SurfaceTexture in preview path
            val analysisUseCase = if (cpuFrameListener != null || faceDetectionListener != null) {
                ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build().apply {
                        setAnalyzer(cpuFrameExecutor) { imageProxy ->
                            processImageProxyForCpuAndFace(imageProxy, facing)
                        }
                    }.also { imageAnalysis = it }
            } else {
                null
            }

            val useCases = mutableListOf<androidx.camera.core.UseCase>()
            useCases.add(preview!!)
            if (analysisUseCase != null) {
                useCases.add(analysisUseCase)
                Log.i(tag, "ImageAnalysis added for CPU ByteArray -> MediaCodec (recording/streaming) + face detection")
            }

            provider.unbindAll()
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                selector,
                *useCases.toTypedArray()
            )

            val info = camera?.cameraInfo
            val hasFlash = info?.hasFlashUnit() ?: false
            val zoomState = info?.zoomState?.value
            state = state.copy(
                facing = facing,
                hasFlash = hasFlash,
                minZoom = zoomState?.minZoomRatio ?: 1f,
                maxZoom = zoomState?.maxZoomRatio ?: 1f,
                zoomRatio = zoomState?.zoomRatio ?: 1f,
                isPreviewActive = true,
                error = null
            )

            Log.i(tag, "Camera bound PURE PreviewView facing=$facing hasFlash=$hasFlash – ZERO GL in preview, CPU frames for recording/streaming")

            try {
                com.androidvirtualcam.voice.VoiceChangerEngine.getInstance()?.setCameraRunning(true)
            } catch (e: Exception) {
                Log.w(tag, "Failed to notify VoiceChangerEngine", e)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(tag, "bindPreview failed", e)
            state = state.copy(error = e.message, isPreviewActive = false)
            Result.failure(e)
        }
    }

    /**
     * Bind with PreviewView directly – pure preview, no GL.
     */
    fun bindWithPreviewView(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        facing: CameraFacing = state.facing
    ): Result<Unit> {
        return bindPreview(lifecycleOwner, previewView.surfaceProvider, facing)
    }

    private fun processImageProxyForCpuAndFace(imageProxy: ImageProxy, facing: CameraFacing) {
        try {
            val rotation = imageProxy.imageInfo.rotationDegrees
            val width = imageProxy.width
            val height = imageProxy.height

            // Face detection for Center Stage (if listener set)
            faceDetectionListener?.let { listener ->
                try {
                    val mediaImage = imageProxy.image
                    if (mediaImage != null) {
                        val inputImage = InputImage.fromMediaImage(mediaImage, rotation)
                        val isMirrored = facing == CameraFacing.FRONT
                        listener(inputImage, isMirrored)
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Face detection failed", e)
                }
            }

            // CPU frame processing for recording/streaming – ByteArray -> MediaCodec
            cpuFrameListener?.let { listener ->
                try {
                    // Throttle to ~30fps for CPU to avoid overload
                    val now = System.currentTimeMillis()
                    if (now - lastCpuFrameTime < 33) {
                        // Skip if too frequent, but still need to close imageProxy
                    } else {
                        lastCpuFrameTime = now
                        val nv21 = yuv420ToNv21(imageProxy)
                        if (nv21 != null) {
                            listener(nv21, width, height, imageProxy.imageInfo.timestamp)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(tag, "CPU frame conversion failed", e)
                }
            }

        } catch (e: Exception) {
            Log.w(tag, "processImageProxyForCpuAndFace failed", e)
        } finally {
            imageProxy.close()
        }
    }

    /**
     * Convert YUV_420_888 ImageProxy to NV21 ByteArray for CPU processing -> MediaCodec
     * This is the core of the new CPU pipeline: no GL, just ByteArray.
     */
    private fun yuv420ToNv21(image: ImageProxy): ByteArray? {
        return try {
            val yBuffer = image.planes[0].buffer
            val uBuffer = image.planes[1].buffer
            val vBuffer = image.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)

            // Y plane
            yBuffer.get(nv21, 0, ySize)

            // UV planes – NV21 is Y + VU interleaved
            // YUV_420_888 has U and V planes separate, need to interleave as VU
            // Simplified: copy V then U interleaved
            // For many devices, U and V are already interleaved in a specific way, but we do generic conversion
            val width = image.width
            val height = image.height
            val yRowStride = image.planes[0].rowStride
            val uvRowStride = image.planes[1].rowStride
            val uvPixelStride = image.planes[1].pixelStride

            // If pixelStride is 2 and rowStride matches, we can do fast path, but for simplicity do generic
            // For CPU pipeline, we convert to NV21: Y plane full, then VU interleaved subsampled
            // Use standard conversion from YUV_420_888 to NV21

            // Rewind buffers
            yBuffer.rewind()
            uBuffer.rewind()
            vBuffer.rewind()

            // Y
            var pos = 0
            if (yRowStride == width) {
                yBuffer.get(nv21, 0, ySize)
                pos = ySize
            } else {
                // Copy row by row
                for (row in 0 until height) {
                    yBuffer.position(row * yRowStride)
                    yBuffer.get(nv21, pos, width)
                    pos += width
                }
            }

            // UV – NV21 expects VU interleaved
            val uvHeight = height / 2
            val uvWidth = width / 2

            // For NV21, after Y, we have VU VU ...
            // YUV_420_888: U and V planes may have pixelStride
            // We'll iterate and interleave V and U
            for (row in 0 until uvHeight) {
                val uRowStart = row * uvRowStride
                val vRowStart = row * image.planes[2].rowStride
                for (col in 0 until uvWidth) {
                    val uPos = uRowStart + col * uvPixelStride
                    val vPos = vRowStart + col * image.planes[2].pixelStride
                    if (vPos < vBuffer.capacity() && uPos < uBuffer.capacity()) {
                        // NV21: V first, then U
                        nv21[pos++] = vBuffer.get(vPos)
                        nv21[pos++] = uBuffer.get(uPos)
                    }
                }
            }

            nv21
        } catch (e: Exception) {
            Log.e(tag, "yuv420ToNv21 failed", e)
            null
        }
    }

    // Legacy method kept for compatibility but no longer uses SurfaceTexture – now pure PreviewView
    fun bindWithSurfaceTexture(
        lifecycleOwner: LifecycleOwner,
        surfaceTexture: android.graphics.SurfaceTexture,
        facing: CameraFacing = state.facing,
        executor: Executor = ContextCompat.getMainExecutor(context)
    ): Result<Unit> {
        Log.w(tag, "bindWithSurfaceTexture called but now using pure PreviewView – ignoring SurfaceTexture, using PreviewView fallback")
        // This method previously used SurfaceTexture + OES texture + GL – now removed
        // We will create a PreviewView-less binding? For compatibility, we try to bind preview with a dummy surface provider
        // Actually we should not use SurfaceTexture at all in preview path – return failure and let caller use bindWithPreviewView
        return Result.failure(IllegalStateException("SurfaceTexture path removed – use bindWithPreviewView for pure CameraX PreviewView, ZERO GL"))
    }

    fun switchCamera(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider
    ): Result<Unit> {
        val newFacing = if (state.facing == CameraFacing.BACK) CameraFacing.FRONT else CameraFacing.BACK
        return bindPreview(lifecycleOwner, surfaceProvider, newFacing)
    }

    fun switchCameraWithPreviewView(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView
    ): Result<Unit> {
        val newFacing = if (state.facing == CameraFacing.BACK) CameraFacing.FRONT else CameraFacing.BACK
        return bindWithPreviewView(lifecycleOwner, previewView, newFacing)
    }

    fun toggleTorch(): Result<Unit> {
        val cam = camera ?: return Result.failure(IllegalStateException("Camera not bound"))
        if (!state.hasFlash) return Result.failure(IllegalStateException("Flash not available"))
        return try {
            val newTorch = !state.isTorchOn
            cam.cameraControl.enableTorch(newTorch)
            state = state.copy(isTorchOn = newTorch)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun setZoom(ratio: Float): Result<Unit> {
        val cam = camera ?: return Result.failure(IllegalStateException("Camera not bound"))
        val clamped = ratio.coerceIn(state.minZoom, state.maxZoom)
        return try {
            cam.cameraControl.setZoomRatio(clamped)
            state = state.copy(zoomRatio = clamped)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun unbindAll() {
        cameraProvider?.unbindAll()
        state = state.copy(isPreviewActive = false)
    }

    fun getVideoCapture(): VideoCapture<Recorder>? = videoCapture
}

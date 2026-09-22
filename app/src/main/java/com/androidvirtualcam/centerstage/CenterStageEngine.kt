package com.androidvirtualcam.centerstage

import android.graphics.RectF
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Center Stage Engine – ML Kit Face Detection at ~15fps, computes target crop.
 *
 * Like Apple Center Stage:
 * - Detects faces, computes group bounding box
 * - Adds padding, calculates zoom to keep face at ~30% frame height
 * - Outputs smoothed centerX,centerY,zoom via StateFlow
 *
 * Works on front and rear cameras, handles mirroring.
 */
class CenterStageEngine {

    companion object {
        private const val TAG = "CenterStageEngine"
        private const val MIN_FACE_SIZE = 0.15f
        private const val DEFAULT_PADDING = 1.8f
        private const val HEADROOM_FACTOR = 1.2f // extra top padding for headroom
        private const val MAX_FACES = 5
    }

    private val detector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(MIN_FACE_SIZE)
            .enableTracking()
            .build()
        FaceDetection.getClient(options)
    }

    private val smoother = CenterStageSmoother()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _state = MutableStateFlow(CenterStageState())
    val state: StateFlow<CenterStageState> = _state

    private var isProcessing = false
    private var lastProcessMs = 0L
    private val processIntervalMs = 66L // ~15fps for face detection

    private var trackingMode = CenterStageState.TrackingMode.GROUP
    private var maxZoom = 3f
    private var minZoom = 1f
    private var paddingFactor = DEFAULT_PADDING
    private var isEnabled = false

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        _state.value = _state.value.copy(isEnabled = enabled)
        if (!enabled) {
            smoother.reset()
            _state.value = CenterStageState(
                isEnabled = false,
                centerX = 0.5f,
                centerY = 0.5f,
                zoom = 1f,
                targetCenterX = 0.5f,
                targetCenterY = 0.5f,
                targetZoom = 1f
            )
        }
        Log.i(TAG, "CenterStage ${if (enabled) "enabled" else "disabled"}")
    }

    fun setTrackingMode(mode: CenterStageState.TrackingMode) {
        trackingMode = mode
        _state.value = _state.value.copy(trackingMode = mode)
    }

    fun setMaxZoom(zoom: Float) {
        maxZoom = zoom.coerceIn(1f, 5f)
        _state.value = _state.value.copy(maxZoom = maxZoom)
    }

    fun setPaddingFactor(padding: Float) {
        paddingFactor = padding.coerceIn(1.2f, 3.5f)
        _state.value = _state.value.copy(paddingFactor = padding)
    }

    fun setSmoothingSpeed(panSpeed: Float, zoomSpeed: Float) {
        smoother.panSpeed = panSpeed
        smoother.zoomSpeed = zoomSpeed
    }

    /**
     * Process InputImage from CameraX ImageAnalysis.
     * Called from analyzer thread – uses ML Kit Task API (no coroutines await needed).
     */
    fun processInputImage(image: InputImage, isMirrored: Boolean) {
        if (!isEnabled) return
        val now = System.currentTimeMillis()
        if (now - lastProcessMs < processIntervalMs) return
        if (isProcessing) return
        isProcessing = true
        lastProcessMs = now

        try {
            detector.process(image)
                .addOnSuccessListener { faces ->
                    try {
                        handleFaces(faces.map { face ->
                            val box = face.boundingBox
                            val normalized = RectF(
                                box.left.toFloat() / image.width,
                                box.top.toFloat() / image.height,
                                box.right.toFloat() / image.width,
                                box.bottom.toFloat() / image.height
                            )
                            val finalBox = if (isMirrored) {
                                RectF(
                                    1f - normalized.right,
                                    normalized.top,
                                    1f - normalized.left,
                                    normalized.bottom
                                )
                            } else normalized

                            DetectedFace(
                                boundingBox = finalBox,
                                rawBox = box,
                                trackingId = face.trackingId,
                                headEulerY = face.headEulerAngleY,
                                headEulerZ = face.headEulerAngleZ,
                                confidence = 1f
                            )
                        }, image.width, image.height, isMirrored)
                    } catch (e: Exception) {
                        Log.w(TAG, "Face mapping failed: ${e.message}")
                    } finally {
                        isProcessing = false
                    }
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Face detection failed: ${e.message}")
                    isProcessing = false
                }
        } catch (e: Exception) {
            Log.w(TAG, "Face detection exception: ${e.message}")
            isProcessing = false
        }
    }

    /**
     * Process bitmap – fallback for when using GL readback or preview bitmap.
     */
    fun processBitmap(
        bitmap: android.graphics.Bitmap,
        isMirrored: Boolean,
        rotationDegrees: Int = 0
    ) {
        if (!isEnabled) return
        val now = System.currentTimeMillis()
        if (now - lastProcessMs < processIntervalMs) return
        if (isProcessing) return
        isProcessing = true
        lastProcessMs = now

        try {
            val image = InputImage.fromBitmap(bitmap, rotationDegrees)
            detector.process(image)
                .addOnSuccessListener { faces ->
                    try {
                        handleFaces(faces.map { face ->
                            val box = face.boundingBox
                            val normalized = RectF(
                                box.left.toFloat() / image.width,
                                box.top.toFloat() / image.height,
                                box.right.toFloat() / image.width,
                                box.bottom.toFloat() / image.height
                            )
                            val finalBox = if (isMirrored) {
                                RectF(1f - normalized.right, normalized.top, 1f - normalized.left, normalized.bottom)
                            } else normalized

                            DetectedFace(
                                boundingBox = finalBox,
                                rawBox = box,
                                trackingId = face.trackingId,
                                headEulerY = face.headEulerAngleY,
                                headEulerZ = face.headEulerAngleZ,
                                confidence = 1f
                            )
                        }, image.width, image.height, isMirrored)
                    } catch (e: Exception) {
                        Log.w(TAG, "Bitmap face mapping failed: ${e.message}")
                    } finally {
                        isProcessing = false
                    }
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Bitmap face detection failed: ${e.message}")
                    isProcessing = false
                }
        } catch (e: Exception) {
            isProcessing = false
            Log.w(TAG, "Failed to create InputImage from bitmap: ${e.message}")
        }
    }

    private fun handleFaces(detectedFaces: List<DetectedFace>, imgW: Int, imgH: Int, isMirrored: Boolean) {
        if (detectedFaces.isEmpty()) {
            // No face – let smoother handle return to center
            smoother.setFaceLost()
            val smoothed = smoother.update()
            _state.value = _state.value.copy(
                isTracking = false,
                faceCount = 0,
                centerX = smoothed.centerX,
                centerY = smoothed.centerY,
                zoom = smoothed.zoom,
                boundingBox = null,
                confidence = 0f
            )
            return
        }

        // Limit faces
        val faces = detectedFaces.take(MAX_FACES)

        // Compute bounding box based on mode
        val targetBox: RectF
        val targetCenter: Pair<Float, Float>
        val targetZoom: Float

        if (trackingMode == CenterStageState.TrackingMode.SINGLE) {
            // Pick largest face
            val largest = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }!!
            targetBox = largest.boundingBox
            val padded = padBox(targetBox, paddingFactor)
            targetCenter = computeCenterWithHeadroom(padded, largest.boundingBox)
            targetZoom = computeZoomForBox(padded)
        } else {
            // GROUP – union of all faces
            val union = computeUnionBox(faces.map { it.boundingBox })
            val padded = padBox(union, paddingFactor)
            // For group, center is union center, but add slight headroom based on average top
            targetBox = union
            targetCenter = Pair(
                padded.centerX(),
                (padded.centerY() * 0.9f + union.centerY() * 0.1f).coerceIn(0f, 1f) // slight upward bias for headroom
            )
            targetZoom = computeZoomForBox(padded)
        }

        // Update smoother target
        smoother.setTarget(targetCenter.first, targetCenter.second, targetZoom)

        // Get smoothed values
        val smoothed = smoother.update()

        _state.value = _state.value.copy(
            isTracking = true,
            centerX = smoothed.centerX,
            centerY = smoothed.centerY,
            zoom = smoothed.zoom,
            targetCenterX = targetCenter.first,
            targetCenterY = targetCenter.second,
            targetZoom = targetZoom,
            faceCount = faces.size,
            boundingBox = targetBox,
            confidence = 1f
        )
    }

    // For state copy with isMirrored we need extra field – we store in separate var, but for simplicity add to state via copy hack?
    // We'll keep state as is, mirroring handled in detection.

    private fun computeUnionBox(boxes: List<RectF>): RectF {
        if (boxes.isEmpty()) return RectF(0.3f, 0.3f, 0.7f, 0.7f)
        var left = 1f
        var top = 1f
        var right = 0f
        var bottom = 0f
        for (b in boxes) {
            left = minOf(left, b.left)
            top = minOf(top, b.top)
            right = maxOf(right, b.right)
            bottom = maxOf(bottom, b.bottom)
        }
        return RectF(left, top, right, bottom)
    }

    private fun padBox(box: RectF, padding: Float): RectF {
        val w = box.width()
        val h = box.height()
        val centerX = box.centerX()
        val centerY = box.centerY()

        // Apply padding – grow box
        val paddedW = (w * padding).coerceAtMost(1f)
        val paddedH = (h * padding * HEADROOM_FACTOR).coerceAtMost(1f)

        val left = (centerX - paddedW / 2f).coerceIn(0f, 1f)
        val top = (centerY - paddedH / 2f).coerceIn(0f, 1f)
        val right = (centerX + paddedW / 2f).coerceIn(0f, 1f)
        val bottom = (centerY + paddedH / 2f).coerceIn(0f, 1f)

        return RectF(left, top, right, bottom)
    }

    private fun computeCenterWithHeadroom(paddedBox: RectF, originalBox: RectF): Pair<Float, Float> {
        // Add headroom: shift center up slightly (face should be slightly above center, like Apple)
        val centerX = paddedBox.centerX()
        // Original face top is higher than padded top – we want face at upper third
        // So centerY = originalBox.centerY() - (originalBox.height() * 0.15) for headroom
        val headroomOffset = originalBox.height() * 0.1f
        val centerY = (originalBox.centerY() - headroomOffset).coerceIn(0f, 1f)
        return Pair(centerX, centerY)
    }

    private fun computeZoomForBox(paddedBox: RectF): Float {
        val w = paddedBox.width()
        val h = paddedBox.height()
        // Need zoom to fit padded box
        // crop size = 1/zoom, must be >= max(w,h)
        val maxDim = max(w, h)
        if (maxDim <= 0f) return 1f
        val zoom = (1f / maxDim).coerceIn(minZoom, maxZoom)
        return zoom
    }

    fun getCurrentTransform(): Triple<Float, Float, Float> {
        val s = smoother.getCurrent()
        return Triple(s.centerX, s.centerY, s.zoom)
    }

    fun tick() {
        if (!isEnabled) return
        val smoothed = smoother.update()
        _state.value = _state.value.copy(
            centerX = smoothed.centerX,
            centerY = smoothed.centerY,
            zoom = smoothed.zoom
        )
    }

    fun release() {
        try {
            detector.close()
        } catch (_: Exception) {}
    }
}



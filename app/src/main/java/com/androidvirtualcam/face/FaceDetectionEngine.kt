package com.androidvirtualcam.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * FaceDetectionEngine – ML Kit Face Detection with landmarks + contours for beauty & filters
 * 
 * - FAST mode for 30fps, tracks faces, outputs FaceData in normalized 0..1 coords
 * - Provides skin smoothing, face slimming, eye brightening landmarks
 * - Snapchat-style filters need eye, nose, mouth positions
 * 
 * Runs on background thread, throttled to 15fps to save battery
 */
class FaceDetectionEngine(private val context: Context) {

    private val tag = "FaceDetectionEngine"

    private var detector: FaceDetector? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val isProcessing = AtomicBoolean(false)

    private val _faces = MutableStateFlow<List<FaceData>>(emptyList())
    val faces: StateFlow<List<FaceData>> = _faces

    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled

    private var lastDetectTime = 0L
    private val detectIntervalMs = 66L // ~15fps

    private var frameWidth = 1280
    private var frameHeight = 720

    fun initialize(): Boolean {
        return try {
            val options = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .enableTracking()
                .build()

            detector = FaceDetection.getClient(options)
            _isEnabled.value = true
            Log.i(tag, "ML Kit Face Detection initialized FAST + landmarks + contours + tracking")
            true
        } catch (e: Exception) {
            Log.e(tag, "Failed to init face detector", e)
            false
        }
    }

    fun setFrameSize(w: Int, h: Int) {
        frameWidth = w
        frameHeight = h
    }

    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        if (!enabled) {
            _faces.value = emptyList()
        }
    }

    /**
     * Process bitmap – called from CompositorRenderer or CameraInputNode periodically
     * Input bitmap should be 640x480 or smaller for performance
     */
    fun processBitmap(bitmap: Bitmap, rotationDegrees: Int = 0) {
        if (!_isEnabled.value) return
        if (isProcessing.getAndSet(true)) return

        val now = System.currentTimeMillis()
        if (now - lastDetectTime < detectIntervalMs) {
            isProcessing.set(false)
            return
        }
        lastDetectTime = now

        scope.launch {
            try {
                val image = InputImage.fromBitmap(bitmap, rotationDegrees)
                detector?.process(image)
                    ?.addOnSuccessListener { mlFaces ->
                        try {
                            val faceDataList = mlFaces.mapNotNull { face ->
                                convertFace(face, bitmap.width, bitmap.height)
                            }
                            _faces.value = faceDataList
                            if (faceDataList.isNotEmpty()) {
                                FaceLandmarkManager.updateFaces(faceDataList)
                            }
                        } catch (e: Exception) {
                            Log.e(tag, "Convert faces failed", e)
                        } finally {
                            isProcessing.set(false)
                        }
                    }
                    ?.addOnFailureListener { e ->
                        Log.w(tag, "Face detection failed: ${e.message}")
                        isProcessing.set(false)
                    }
            } catch (e: Exception) {
                Log.e(tag, "processBitmap failed", e)
                isProcessing.set(false)
            }
        }
    }

    private fun convertFace(face: Face, bmpW: Int, bmpH: Int): FaceData? {
        try {
            val box = face.boundingBox
            // Normalize to 0..1
            val normBox = RectF(
                box.left.toFloat() / bmpW,
                box.top.toFloat() / bmpH,
                box.right.toFloat() / bmpW,
                box.bottom.toFloat() / bmpH
            )
            // Clamp
            normBox.left = normBox.left.coerceIn(0f, 1f)
            normBox.top = normBox.top.coerceIn(0f, 1f)
            normBox.right = normBox.right.coerceIn(0f, 1f)
            normBox.bottom = normBox.bottom.coerceIn(0f, 1f)

            fun landmark(type: Int): PointF? {
                val lm = face.getLandmark(type) ?: return null
                val pos = lm.position
                return PointF(pos.x / bmpW, pos.y / bmpH)
            }

            fun contour(type: Int): List<PointF> {
                val c = face.getContour(type) ?: return emptyList()
                return c.points.map { p -> PointF(p.x / bmpW, p.y / bmpH) }
            }

            return FaceData(
                id = face.trackingId ?: face.hashCode(),
                boundingBox = normBox,
                leftEye = landmark(FaceLandmark.LEFT_EYE),
                rightEye = landmark(FaceLandmark.RIGHT_EYE),
                nose = landmark(FaceLandmark.NOSE_BASE),
                mouthLeft = landmark(FaceLandmark.MOUTH_LEFT),
                mouthRight = landmark(FaceLandmark.MOUTH_RIGHT),
                mouthBottom = landmark(FaceLandmark.MOUTH_BOTTOM),
                leftEar = landmark(FaceLandmark.LEFT_EAR),
                rightEar = landmark(FaceLandmark.RIGHT_EAR),
                leftCheek = landmark(FaceLandmark.LEFT_CHEEK),
                rightCheek = landmark(FaceLandmark.RIGHT_CHEEK),
                faceContour = contour(FaceContour.FACE),
                leftEyeContour = contour(FaceContour.LEFT_EYE),
                rightEyeContour = contour(FaceContour.RIGHT_EYE),
                upperLipContour = contour(FaceContour.UPPER_LIP_TOP) + contour(FaceContour.UPPER_LIP_BOTTOM),
                lowerLipContour = contour(FaceContour.LOWER_LIP_TOP) + contour(FaceContour.LOWER_LIP_BOTTOM),
                noseBridge = contour(FaceContour.NOSE_BRIDGE),
                leftEyebrow = contour(FaceContour.LEFT_EYEBROW_TOP) + contour(FaceContour.LEFT_EYEBROW_BOTTOM),
                rightEyebrow = contour(FaceContour.RIGHT_EYEBROW_TOP) + contour(FaceContour.RIGHT_EYEBROW_BOTTOM),
                smileProb = face.smilingProbability ?: 0f,
                leftEyeOpenProb = face.leftEyeOpenProbability ?: 1f,
                rightEyeOpenProb = face.rightEyeOpenProbability ?: 1f,
                headEulerX = face.headEulerAngleX,
                headEulerY = face.headEulerAngleY,
                headEulerZ = face.headEulerAngleZ,
                confidence = 1f
            )
        } catch (e: Exception) {
            Log.w(tag, "convertFace failed", e)
            return null
        }
    }

    fun release() {
        try {
            detector?.close()
        } catch (_: Exception) {}
        detector = null
        _faces.value = emptyList()
        _isEnabled.value = false
        scope.cancel()
        Log.i(tag, "FaceDetectionEngine released")
    }
}

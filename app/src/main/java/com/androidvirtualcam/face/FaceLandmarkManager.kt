package com.androidvirtualcam.face

import android.graphics.PointF
import android.graphics.RectF
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicReference

/**
 * FaceLandmarkManager – singleton holding latest detected faces
 * Shared between FaceDetectionEngine (producer) and beauty/filter nodes (consumers)
 * Thread-safe, lock-free for GL thread
 */
object FaceLandmarkManager {

    private val facesRef = AtomicReference<List<FaceData>>(emptyList())

    private val _facesFlow = MutableStateFlow<List<FaceData>>(emptyList())
    val facesFlow: StateFlow<List<FaceData>> = _facesFlow

    // For GL thread quick access – latest faces
    fun getFaces(): List<FaceData> = facesRef.get()

    fun getPrimaryFace(): FaceData? = facesRef.get().firstOrNull()

    fun updateFaces(faces: List<FaceData>) {
        facesRef.set(faces)
        _facesFlow.value = faces
    }

    fun clear() {
        facesRef.set(emptyList())
        _facesFlow.value = emptyList()
    }

    // Helper to get face uniforms for shader – up to 3 faces packed
    // Returns float array: [faceCount, face0 x,y,w,h, leftEye x,y, rightEye x,y, ...]
    fun getFaceUniforms(maxFaces: Int = 3): FloatArray {
        val faces = facesRef.get()
        val result = FloatArray(1 + maxFaces * 16)
        result[0] = faces.size.coerceAtMost(maxFaces).toFloat()
        for (i in 0 until maxFaces) {
            val base = 1 + i * 16
            if (i < faces.size) {
                val f = faces[i]
                result[base] = f.boundingBox.left
                result[base+1] = f.boundingBox.top
                result[base+2] = f.boundingBox.width()
                result[base+3] = f.boundingBox.height()
                result[base+4] = f.leftEye?.x ?: 0f
                result[base+5] = f.leftEye?.y ?: 0f
                result[base+6] = f.rightEye?.x ?: 0f
                result[base+7] = f.rightEye?.y ?: 0f
                result[base+8] = f.nose?.x ?: f.center.x
                result[base+9] = f.nose?.y ?: f.center.y
                result[base+10] = f.mouthLeft?.x ?: f.center.x - f.width*0.1f
                result[base+11] = f.mouthLeft?.y ?: f.center.y + f.height*0.2f
                result[base+12] = f.mouthRight?.x ?: f.center.x + f.width*0.1f
                result[base+13] = f.mouthRight?.y ?: f.center.y + f.height*0.2f
                result[base+14] = f.eyeDistance
                result[base+15] = f.headEulerY
            } else {
                // zero
                for (j in 0 until 16) result[base+j] = 0f
            }
        }
        return result
    }

    // Check if point is inside any face (for skin detection)
    fun isPointInFace(x: Float, y: Float): Boolean {
        return facesRef.get().any { face ->
            x >= face.boundingBox.left && x <= face.boundingBox.right &&
            y >= face.boundingBox.top && y <= face.boundingBox.bottom
        }
    }

    // Get face at point
    fun getFaceAt(x: Float, y: Float): FaceData? {
        return facesRef.get().firstOrNull { face ->
            x >= face.boundingBox.left && x <= face.boundingBox.right &&
            y >= face.boundingBox.top && y <= face.boundingBox.bottom
        }
    }
}

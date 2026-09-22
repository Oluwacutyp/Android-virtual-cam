package com.androidvirtualcam.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androidvirtualcam.compositor.CompositorRenderer
import com.androidvirtualcam.face.FaceDetectionEngine
import com.androidvirtualcam.face.FaceLandmarkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * BeautyViewModel – controls skin smoothing, face slimming, eye brightening
 * Uses face landmarks from ML Kit
 */
class BeautyViewModel(private val context: Context) : ViewModel() {

    private var renderer: CompositorRenderer? = null
    private var faceEngine: FaceDetectionEngine? = null

    private val _isBeautyEnabled = MutableStateFlow(true)
    val isBeautyEnabled: StateFlow<Boolean> = _isBeautyEnabled

    private val _skinSmoothing = MutableStateFlow(0.6f)
    val skinSmoothing: StateFlow<Float> = _skinSmoothing

    private val _faceSlimming = MutableStateFlow(0.5f)
    val faceSlimming: StateFlow<Float> = _faceSlimming

    private val _eyeBrightening = MutableStateFlow(0.6f)
    val eyeBrightening: StateFlow<Float> = _eyeBrightening

    private val _eyeEnlarge = MutableStateFlow(0.1f)
    val eyeEnlarge: StateFlow<Float> = _eyeEnlarge

    private val _isFaceDetectionEnabled = MutableStateFlow(true)
    val isFaceDetectionEnabled: StateFlow<Boolean> = _isFaceDetectionEnabled

    private val _faceCount = MutableStateFlow(0)
    val faceCount: StateFlow<Int> = _faceCount

    private val _currentFilter = MutableStateFlow("sunglasses")
    val currentFilter: StateFlow<String> = _currentFilter

    private val _filterIntensity = MutableStateFlow(1f)
    val filterIntensity: StateFlow<Float> = _filterIntensity

    private val _isFilterEnabled = MutableStateFlow(false)
    val isFilterEnabled: StateFlow<Boolean> = _isFilterEnabled

    init {
        faceEngine = FaceDetectionEngine(context).apply { initialize() }
        viewModelScope.launch {
            FaceLandmarkManager.facesFlow.collect { faces ->
                _faceCount.value = faces.size
            }
        }
        // Start detection loop – captures small bitmap from renderer periodically
        viewModelScope.launch {
            while (true) {
                try {
                    if (_isFaceDetectionEnabled.value && renderer != null) {
                        val bmp = renderer?.captureBitmap()
                        if (bmp != null) {
                            // Downscale to 640x360 for faster detection
                            val small = android.graphics.Bitmap.createScaledBitmap(bmp, 640, 360, true)
                            faceEngine?.processBitmap(small, 0)
                            small.recycle()
                            bmp.recycle()
                        }
                    }
                } catch (e: Exception) {
                    // ignore
                }
                kotlinx.coroutines.delay(150) // ~6-7 fps detection
            }
        }
    }

    fun setRenderer(renderer: CompositorRenderer) {
        this.renderer = renderer
        faceEngine?.setFrameSize(1280, 720)
        faceEngine?.setEnabled(_isFaceDetectionEnabled.value)
    }

    fun setBeautyEnabled(enabled: Boolean) {
        _isBeautyEnabled.value = enabled
        applyToGraph()
    }

    fun setSkinSmoothing(value: Float) {
        _skinSmoothing.value = value.coerceIn(0f, 1f)
        renderer?.updateNodeParameter("skin_smoothing_1", "smoothing", _skinSmoothing.value)
    }

    fun setFaceSlimming(value: Float) {
        _faceSlimming.value = value.coerceIn(0f, 1f)
        renderer?.updateNodeParameter("face_slimming_1", "slimming", _faceSlimming.value)
    }

    fun setEyeBrightening(value: Float) {
        _eyeBrightening.value = value.coerceIn(0f, 1f)
        renderer?.updateNodeParameter("eye_brightening_1", "brightening", _eyeBrightening.value)
    }

    fun setEyeEnlarge(value: Float) {
        _eyeEnlarge.value = value.coerceIn(0f, 0.5f)
        renderer?.updateNodeParameter("eye_brightening_1", "eyeSize", _eyeEnlarge.value)
    }

    fun setFaceDetectionEnabled(enabled: Boolean) {
        _isFaceDetectionEnabled.value = enabled
        faceEngine?.setEnabled(enabled)
    }

    fun setFilter(filterType: String) {
        _currentFilter.value = filterType
        renderer?.updateNodeParameter("face_filter_1", "filterType", filterType)
    }

    fun setFilterIntensity(intensity: Float) {
        _filterIntensity.value = intensity.coerceIn(0f, 1f)
        renderer?.updateNodeParameter("face_filter_1", "intensity", intensity)
    }

    fun setFilterEnabled(enabled: Boolean) {
        _isFilterEnabled.value = enabled
        // Toggle filter node presence via graph? For now update param
        renderer?.updateNodeParameter("face_filter_1", "intensity", if (enabled) _filterIntensity.value else 0f)
    }

    private fun applyToGraph() {
        val r = renderer ?: return
        r.updateNodeParameter("skin_smoothing_1", "smoothing", if (_isBeautyEnabled.value) _skinSmoothing.value else 0f)
        r.updateNodeParameter("face_slimming_1", "slimming", if (_isBeautyEnabled.value) _faceSlimming.value else 0f)
        r.updateNodeParameter("eye_brightening_1", "brightening", if (_isBeautyEnabled.value) _eyeBrightening.value else 0f)
    }

    fun getAvailableFilters(): List<String> {
        return listOf("sunglasses", "glasses", "hat", "crown", "mask", "dog", "cat", "mustache", "clown")
    }

    override fun onCleared() {
        super.onCleared()
        faceEngine?.release()
    }
}

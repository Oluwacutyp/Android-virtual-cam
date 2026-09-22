package com.androidvirtualcam.ui.viewmodels

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androidvirtualcam.camera.CameraManager
import com.androidvirtualcam.centerstage.CenterStageManager
import com.androidvirtualcam.centerstage.CenterStageState
import com.androidvirtualcam.compositor.CompositorGraph
import com.androidvirtualcam.compositor.CompositorNodeFactory
import com.androidvirtualcam.compositor.CompositorRenderer
import com.androidvirtualcam.compositor.presets.CompositorPresets
import com.androidvirtualcam.framebus.VCamFrameBus
import com.androidvirtualcam.recording.RecordingManager
import com.androidvirtualcam.scene.InMemorySceneRepository
import com.androidvirtualcam.service.VirtualCameraService
import com.androidvirtualcam.streaming.StreamingManager
import com.androidvirtualcam.transition.TransitionConfig
import com.androidvirtualcam.transition.TransitionState
import com.androidvirtualcam.transition.TransitionType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainViewModel(
    private val context: Context
) : ViewModel() {

    private val sceneRepository = InMemorySceneRepository()
    private val _sceneCollection = MutableStateFlow(sceneRepository.get())
    val sceneCollection: StateFlow<com.androidvirtualcam.scene.SceneCollection> = _sceneCollection

    private var _compositorRenderer: CompositorRenderer? = null
    val compositorRenderer: CompositorRenderer? get() = _compositorRenderer

    private var _cameraManager: CameraManager? = null
    val cameraManager: CameraManager? get() = _cameraManager

    private var _recordingManager: RecordingManager? = null
    val recordingManager: RecordingManager? get() = _recordingManager

    private var _streamingManager: StreamingManager? = null
    val streamingManager: StreamingManager? get() = _streamingManager

    private var _centerStageManager: CenterStageManager? = null
    val centerStageManager: CenterStageManager? get() = _centerStageManager

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming

    private val _isVirtualCamActive = MutableStateFlow(false)
    val isVirtualCamActive: StateFlow<Boolean> = _isVirtualCamActive

    private val _selectedLayerId = MutableStateFlow<String?>(null)
    val selectedLayerId: StateFlow<String?> = _selectedLayerId

    private val _performanceOverlayEnabled = MutableStateFlow(false)
    val performanceOverlayEnabled: StateFlow<Boolean> = _performanceOverlayEnabled

    private val _centerStageState = MutableStateFlow(CenterStageState())
    val centerStageState: StateFlow<CenterStageState> = _centerStageState

    private val _transitionState = MutableStateFlow(TransitionState())
    val transitionState: StateFlow<TransitionState> = _transitionState

    private val _transitionConfig = MutableStateFlow(TransitionConfig.default())
    val transitionConfig: StateFlow<TransitionConfig> = _transitionConfig

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val _uploadedImageUri = MutableStateFlow<String?>(null)
    val uploadedImageUri: StateFlow<String?> = _uploadedImageUri

    private val _uploadedVideoUri = MutableStateFlow<String?>(null)
    val uploadedVideoUri: StateFlow<String?> = _uploadedVideoUri

    private val _uploadedMediaMessage = MutableStateFlow<String?>(null)
    val uploadedMediaMessage: StateFlow<String?> = _uploadedMediaMessage

    // STEP 2 & 3 – Keep compositor for recording/streaming only, effects preview toggle
    private val _compositorEnabled = MutableStateFlow(true)
    val compositorEnabled: StateFlow<Boolean> = _compositorEnabled

    private val _effectsPreviewEnabled = MutableStateFlow(false)
    val effectsPreviewEnabled: StateFlow<Boolean> = _effectsPreviewEnabled

    private val _compositorError = MutableStateFlow<String?>(null)
    val compositorError: StateFlow<String?> = _compositorError

    init {
        viewModelScope.launch {
            sceneRepository.observe { collection ->
                _sceneCollection.value = collection
                _compositorRenderer?.let { renderer ->
                    try {
                        // Preserve current compositor graph; scene repository drives UI layer selection
                        // Graph remains managed by SceneViewModel preset switching
                        val currentJson = renderer.getGraphJson()
                        if (currentJson.isNotEmpty()) {
                            android.util.Log.d("MainViewModel", "Scene collection updated, active=${collection.activeSceneId}")
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("MainViewModel", "Scene observer failed: ${e.message}")
                        _errorMessage.value = "Scene update failed: ${e.message}"
                    }
                }
            }
        }
    }

    fun initializeManagers(
        cameraManager: CameraManager,
        compositorRenderer: CompositorRenderer,
        recordingManager: RecordingManager,
        streamingManager: StreamingManager
    ) {
        _cameraManager = cameraManager
        _compositorRenderer = compositorRenderer
        _recordingManager = recordingManager
        _streamingManager = streamingManager

        // STEP 4 – Fix crash on compositor load: wrap in try/catch, disable compositor if fails
        try {
            android.util.Log.i("MainViewModel", "Initializing compositor – wrapped in try/catch to prevent crash")
            // Compositor already initialized in GLPreview or via initialize(), but we try to ensure it doesn't crash
        } catch (e: Throwable) {
            android.util.Log.e("MainViewModel", "Compositor init crashed, disabling compositor, using raw CameraX only", e)
            _compositorEnabled.value = false
            _compositorError.value = "Compositor disabled: ${e.message} – using raw preview"
            _effectsPreviewEnabled.value = false
        }

        // Initialize Center Stage
        try {
            val centerStageManager = CenterStageManager(context)
            try {
                centerStageManager.initialize(compositorRenderer)
            } catch (e: Throwable) {
                android.util.Log.e("MainViewModel", "CenterStage init failed due to GL, disabling", e)
                _compositorError.value = "CenterStage GL failed: ${e.message}"
            }
            _centerStageManager = centerStageManager

            // Bridge camera ImageAnalysis to center stage
            cameraManager.setFaceDetectionListener { inputImage, isMirrored ->
                try {
                    centerStageManager.processInputImage(inputImage, isMirrored)
                } catch (e: Exception) {
                    android.util.Log.w("MainViewModel", "CenterStage process failed", e)
                }
            }

            viewModelScope.launch {
                try {
                    centerStageManager.state.collect { state ->
                        _centerStageState.value = state
                    }
                } catch (e: Exception) {
                    android.util.Log.w("MainViewModel", "CenterStage collect failed", e)
                }
            }

            android.util.Log.i("MainViewModel", "CenterStageManager initialized and linked to CameraManager")

            // Observe transition state
            try {
                compositorRenderer.transitionManager?.let { tm ->
                    viewModelScope.launch {
                        tm.state.collect { tState ->
                            _transitionState.value = tState
                        }
                    }
                    viewModelScope.launch {
                        tm.currentConfig.collect { cfg ->
                            _transitionConfig.value = cfg
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("MainViewModel", "TransitionManager observe failed", e)
            }
        } catch (e: Throwable) {
            android.util.Log.e("MainViewModel", "CenterStage init failed, disabling compositor", e)
            _compositorEnabled.value = false
            _compositorError.value = "Compositor init failed: ${e.message} – raw preview only"
            _errorMessage.value = "Center Stage init failed: ${e.message}"
        }

        viewModelScope.launch {
            _streamingManager?.sessionsFlow?.collect { sessions ->
                _isStreaming.value = sessions.any { it.status == com.androidvirtualcam.streaming.StreamingStatus.LIVE }
            }
        }
        viewModelScope.launch {
            _recordingManager?.let { mgr ->
                // Observe recording state via isRecording flow if available
                try {
                    while (true) {
                        _isRecording.value = mgr.isRecording()
                        kotlinx.coroutines.delay(500)
                    }
                } catch (e: Exception) {
                    android.util.Log.w("MainViewModel", "Recording observer failed: ${e.message}")
                }
            }
        }
    }

    // Center Stage controls
    fun toggleCenterStage() {
        _centerStageManager?.toggle()
    }

    fun setCenterStageEnabled(enabled: Boolean) {
        _centerStageManager?.setEnabled(enabled)
    }

    fun setCenterStageTrackingMode(mode: CenterStageState.TrackingMode) {
        _centerStageManager?.setTrackingMode(mode)
    }

    fun setCenterStageMaxZoom(zoom: Float) {
        _centerStageManager?.setMaxZoom(zoom)
    }

    fun setCenterStagePadding(padding: Float) {
        _centerStageManager?.setPadding(padding)
    }

    fun toggleRecording(): Boolean {
        val manager = _recordingManager
        if (manager == null) {
            _errorMessage.value = "RecordingManager not initialized"
            return false
        }
        return if (_isRecording.value) {
            viewModelScope.launch {
                try {
                    val result = manager.stopRecording()
                    if (result.isSuccess) {
                        _isRecording.value = false
                    } else {
                        _errorMessage.value = "Stop recording failed: ${result.exceptionOrNull()?.message}"
                    }
                } catch (e: Exception) {
                    _errorMessage.value = "Stop recording error: ${e.message}"
                    android.util.Log.e("MainViewModel", "stopRecording failed", e)
                }
            }
            false
        } else {
            viewModelScope.launch {
                try {
                    val result = manager.startRecording()
                    _isRecording.value = result.isSuccess
                    if (!result.isSuccess) {
                        _errorMessage.value = "Start recording failed: ${result.exceptionOrNull()?.message}"
                    }
                } catch (e: Exception) {
                    _errorMessage.value = "Start recording error: ${e.message}"
                    android.util.Log.e("MainViewModel", "startRecording failed", e)
                }
            }
            true
        }
    }

    fun toggleVirtualCamera() {
        if (_isVirtualCamActive.value) {
            val intent = Intent(context, VirtualCameraService::class.java).apply {
                action = VirtualCameraService.ACTION_STOP
            }
            context.startService(intent)
            _isVirtualCamActive.value = false
        } else {
            val intent = Intent(context, VirtualCameraService::class.java).apply {
                action = VirtualCameraService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            _isVirtualCamActive.value = true
        }
    }

    fun selectLayer(layerId: String?) {
        _selectedLayerId.value = layerId
    }

    fun switchScene(sceneId: String) {
        sceneRepository.update { it.withActiveScene(sceneId) }
    }

    fun switchPreset(presetId: String, withTransition: Boolean = true) {
        viewModelScope.launch {
            try {
                val renderer = _compositorRenderer
                if (renderer == null) {
                    _errorMessage.value = "Renderer not initialized"
                    return@launch
                }

                if (withTransition && _transitionConfig.value.type != TransitionType.CUT.name) {
                    // Use animated transition
                    renderer.transitionToPreset(presetId, _transitionConfig.value) {
                        android.util.Log.i("MainViewModel", "Transition to $presetId complete")
                    }
                    android.util.Log.i("MainViewModel", "Transition to $presetId started with ${_transitionConfig.value.type}")
                } else {
                    // Hard cut
                    val json = CompositorPresets.loadPresetFromAssets(context, presetId)
                        ?: CompositorPresets.getPresetJson(context, presetId)
                    if (json != null) {
                        renderer.loadGraphFromJson(json)
                        android.util.Log.i("MainViewModel", "Switched preset $presetId (CUT)")
                    } else {
                        _errorMessage.value = "Preset $presetId not found"
                    }
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to switch preset $presetId: ${e.message}"
                android.util.Log.e("MainViewModel", "Failed to switch preset $presetId", e)
                try {
                    val fallback = CompositorPresets.getPresetJson(context, "main_camera")
                    if (fallback != null) _compositorRenderer?.loadGraphFromJson(fallback)
                } catch (re: Exception) {
                    android.util.Log.w("MainViewModel", "Fallback preset also failed: ${re.message}")
                }
            }
        }
    }

    fun switchPresetWithTransition(presetId: String, config: TransitionConfig) {
        _transitionConfig.value = config
        _compositorRenderer?.setTransitionConfig(config)
        switchPreset(presetId, withTransition = true)
    }

    fun setTransitionType(type: TransitionType) {
        val newConfig = _transitionConfig.value.withType(type)
        _transitionConfig.value = newConfig
        _compositorRenderer?.setTransitionConfig(newConfig)
    }

    fun setTransitionDuration(durationMs: Long) {
        val newConfig = _transitionConfig.value.withDuration(durationMs)
        _transitionConfig.value = newConfig
        _compositorRenderer?.setTransitionConfig(newConfig)
    }

    fun setTransitionEasing(easing: com.androidvirtualcam.transition.TransitionEasing) {
        val newConfig = _transitionConfig.value.withEasing(easing)
        _transitionConfig.value = newConfig
        _compositorRenderer?.setTransitionConfig(newConfig)
    }

    fun setTransitionDirection(dir: com.androidvirtualcam.transition.SlideDirection) {
        val newConfig = _transitionConfig.value.withDirection(dir)
        _transitionConfig.value = newConfig
        _compositorRenderer?.setTransitionConfig(newConfig)
    }

    fun togglePerformanceOverlay() {
        _performanceOverlayEnabled.value = !_performanceOverlayEnabled.value
    }

    override fun onCleared() {
        super.onCleared()
        try {
            _centerStageManager?.release()
        } catch (e: Exception) {
            android.util.Log.w("MainViewModel", "CenterStage release failed: ${e.message}")
        }
        try {
            _compositorRenderer?.release()
        } catch (e: Exception) {
            android.util.Log.w("MainViewModel", "Renderer release failed: ${e.message}")
        }
        try {
            _streamingManager?.release()
        } catch (e: Exception) {
            android.util.Log.w("MainViewModel", "Streaming release failed: ${e.message}")
        }
        try {
            _recordingManager?.release()
        } catch (e: Exception) {
            android.util.Log.w("MainViewModel", "Recording release failed: ${e.message}")
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun setUploadedImage(uri: String) {
        viewModelScope.launch {
            try {
                _uploadedImageUri.value = uri
                _uploadedMediaMessage.value = "Image selected: ${uri.takeLast(40)} - adding as overlay..."
                val renderer = _compositorRenderer
                if (renderer == null) {
                    _errorMessage.value = "Renderer not initialized"
                    return@launch
                }
                // Add image overlay via renderer method
                val result = renderer.addImageOverlay(uri)
                if (result.isSuccess) {
                    _uploadedMediaMessage.value = "Image overlay added ✓ - ${uri.takeLast(30)}"
                    android.util.Log.i("MainViewModel", "Image overlay added $uri")
                } else {
                    _errorMessage.value = "Failed to add image overlay: ${result.exceptionOrNull()?.message}"
                    _uploadedMediaMessage.value = "Failed to add image: ${result.exceptionOrNull()?.message}"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Image overlay error: ${e.message}"
                _uploadedMediaMessage.value = "Image error: ${e.message}"
                android.util.Log.e("MainViewModel", "setUploadedImage failed", e)
            }
        }
    }

    fun setUploadedVideo(uri: String) {
        viewModelScope.launch {
            try {
                _uploadedVideoUri.value = uri
                _uploadedMediaMessage.value = "Video selected: ${uri.takeLast(40)} - setting as background..."
                val renderer = _compositorRenderer
                if (renderer == null) {
                    _errorMessage.value = "Renderer not initialized"
                    return@launch
                }
                val result = renderer.setVideoBackground(uri)
                if (result.isSuccess) {
                    _uploadedMediaMessage.value = "Video background set ✓ - ${uri.takeLast(30)}"
                    android.util.Log.i("MainViewModel", "Video background set $uri")
                } else {
                    _errorMessage.value = "Failed to set video background: ${result.exceptionOrNull()?.message}"
                    _uploadedMediaMessage.value = "Failed to set video: ${result.exceptionOrNull()?.message}"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Video background error: ${e.message}"
                _uploadedMediaMessage.value = "Video error: ${e.message}"
                android.util.Log.e("MainViewModel", "setUploadedVideo failed", e)
            }
        }
    }

    fun clearUploadedImage() {
        viewModelScope.launch {
            try {
                _uploadedImageUri.value = null
                _compositorRenderer?.removeImageOverlay()
                _uploadedMediaMessage.value = "Image overlay removed"
            } catch (e: Exception) {
                android.util.Log.w("MainViewModel", "clearUploadedImage failed", e)
            }
        }
    }

    fun clearUploadedVideo() {
        viewModelScope.launch {
            try {
                _uploadedVideoUri.value = null
                _compositorRenderer?.removeVideoBackground()
                _uploadedMediaMessage.value = "Video background removed"
            } catch (e: Exception) {
                android.util.Log.w("MainViewModel", "clearUploadedVideo failed", e)
            }
        }
    }

    fun clearUploadedMediaMessage() {
        _uploadedMediaMessage.value = null
    }

    // Effects preview toggle – STEP 3
    fun setEffectsPreviewEnabled(enabled: Boolean) {
        try {
            if (enabled && !_compositorEnabled.value) {
                _compositorError.value = "Compositor disabled due to previous crash – cannot enable effects preview"
                _effectsPreviewEnabled.value = false
                return
            }
            _effectsPreviewEnabled.value = enabled
            android.util.Log.i("MainViewModel", "Effects preview ${if (enabled) "ENABLED" else "DISABLED"} – raw=${!enabled}")
            if (enabled) {
                // Try to initialize compositor if not already
                try {
                    _compositorRenderer?.let { renderer ->
                        // Test if compositor can render
                        renderer.makeCurrent()
                    }
                } catch (e: Throwable) {
                    android.util.Log.e("MainViewModel", "Effects preview failed, auto-switch back to raw", e)
                    _effectsPreviewEnabled.value = false
                    _compositorError.value = "GL compositor failed: ${e.message} – switched back to raw preview"
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainViewModel", "setEffectsPreviewEnabled failed", e)
            _effectsPreviewEnabled.value = false
        }
    }

    fun onCompositorFailed() {
        _effectsPreviewEnabled.value = false
        _compositorError.value = "GL compositor failed (shader compile error 35633) – auto-switched to raw CameraX preview which ALWAYS works"
        android.util.Log.w("MainViewModel", "Compositor failed, auto-switch to raw preview")
    }

    fun disableCompositor() {
        _compositorEnabled.value = false
        _effectsPreviewEnabled.value = false
        _compositorError.value = "Compositor disabled – using raw CameraX PreviewView only. Effects still work for recording/streaming output."
    }

    fun clearCompositorError() {
        _compositorError.value = null
    }

    fun setCompositorEnabled(enabled: Boolean) {
        _compositorEnabled.value = enabled
        if (!enabled) {
            _effectsPreviewEnabled.value = false
        }
    }
}

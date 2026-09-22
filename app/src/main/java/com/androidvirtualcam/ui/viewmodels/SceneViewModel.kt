package com.androidvirtualcam.ui.viewmodels

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androidvirtualcam.compositor.CompositorGraph
import com.androidvirtualcam.compositor.CompositorNodeFactory
import com.androidvirtualcam.compositor.CompositorRenderer
import com.androidvirtualcam.compositor.presets.CompositorPresets
import com.androidvirtualcam.transition.TransitionConfig
import com.androidvirtualcam.transition.TransitionState
import com.androidvirtualcam.transition.TransitionType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ScenePresetUi(
    val id: String,
    val name: String,
    val description: String,
    val thumbnail: Bitmap? = null,
    val isActive: Boolean = false
)

class SceneViewModel(
    private val context: Context
) : ViewModel() {

    private val _presets = MutableStateFlow<List<ScenePresetUi>>(emptyList())
    val presets: StateFlow<List<ScenePresetUi>> = _presets

    private val _activePresetId = MutableStateFlow("main_camera")
    val activePresetId: StateFlow<String> = _activePresetId

    private var compositorRenderer: CompositorRenderer? = null

    private val _transitionState = MutableStateFlow(TransitionState())
    val transitionState: StateFlow<TransitionState> = _transitionState

    private val _transitionConfig = MutableStateFlow(TransitionConfig.default())
    val transitionConfig: StateFlow<TransitionConfig> = _transitionConfig

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    init {
        loadPresets()
    }

    fun setRenderer(renderer: CompositorRenderer) {
        compositorRenderer = renderer
        renderer.transitionManager?.let { tm ->
            viewModelScope.launch {
                tm.state.collect { _transitionState.value = it }
            }
            viewModelScope.launch {
                tm.currentConfig.collect { _transitionConfig.value = it }
            }
        }
    }

    private fun loadPresets() {
        viewModelScope.launch {
            try {
                val all = CompositorPresets.getAllPresets(context)
                _presets.value = all.map { preset ->
                    ScenePresetUi(
                        id = preset.id,
                        name = preset.name,
                        description = preset.description,
                        thumbnail = null,
                        isActive = preset.id == _activePresetId.value
                    )
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to load presets: ${e.message}"
                android.util.Log.e("SceneVM", "loadPresets failed", e)
                _presets.value = listOf(
                    ScenePresetUi("main_camera", "Main Camera", "Direct camera feed", null, true)
                )
            }
        }
    }

    fun switchPreset(presetId: String, withTransition: Boolean = true) {
        _activePresetId.value = presetId
        _presets.value = _presets.value.map { it.copy(isActive = it.id == presetId) }

        viewModelScope.launch {
            try {
                val renderer = compositorRenderer
                if (renderer == null) {
                    _errorMessage.value = "Renderer not initialized"
                    return@launch
                }

                if (withTransition && _transitionConfig.value.type != TransitionType.CUT.name) {
                    renderer.transitionToPreset(presetId, _transitionConfig.value) {
                        android.util.Log.i("SceneVM", "Transition to $presetId complete")
                    }
                    android.util.Log.i("SceneVM", "Transition to $presetId started with ${_transitionConfig.value.type}")
                } else {
                    val json = CompositorPresets.loadPresetFromAssets(context, presetId)
                        ?: CompositorPresets.getPresetJson(context, presetId)
                    if (json != null) {
                        renderer.loadGraphFromJson(json)
                        android.util.Log.i("SceneVM", "Switched to preset $presetId (CUT)")
                    } else {
                        _errorMessage.value = "Preset $presetId not found"
                        _activePresetId.value = _presets.value.firstOrNull()?.id ?: "main_camera"
                        _presets.value = _presets.value.map { it.copy(isActive = it.id == _activePresetId.value) }
                    }
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to switch preset $presetId: ${e.message}"
                android.util.Log.e("SceneVM", "Failed to switch preset $presetId", e)
                try {
                    val fallback = CompositorPresets.getPresetJson(context, "main_camera")
                    if (fallback != null) compositorRenderer?.loadGraphFromJson(fallback)
                } catch (re: Exception) {
                    android.util.Log.w("SceneVM", "Fallback also failed: ${re.message}")
                }
            }
        }
    }

    fun switchPresetWithTransition(presetId: String, config: TransitionConfig) {
        _transitionConfig.value = config
        compositorRenderer?.setTransitionConfig(config)
        switchPreset(presetId, withTransition = true)
    }

    fun setTransitionType(type: TransitionType) {
        val newConfig = _transitionConfig.value.withType(type)
        _transitionConfig.value = newConfig
        compositorRenderer?.setTransitionConfig(newConfig)
    }

    fun setTransitionDuration(durationMs: Long) {
        val newConfig = _transitionConfig.value.withDuration(durationMs)
        _transitionConfig.value = newConfig
        compositorRenderer?.setTransitionConfig(newConfig)
    }

    fun setTransitionEasing(easing: com.androidvirtualcam.transition.TransitionEasing) {
        val newConfig = _transitionConfig.value.withEasing(easing)
        _transitionConfig.value = newConfig
        compositorRenderer?.setTransitionConfig(newConfig)
    }

    fun setTransitionDirection(dir: com.androidvirtualcam.transition.SlideDirection) {
        val newConfig = _transitionConfig.value.withDirection(dir)
        _transitionConfig.value = newConfig
        compositorRenderer?.setTransitionConfig(newConfig)
    }

    fun refreshThumbnails() {
        viewModelScope.launch {
            try {
                // Capture live thumbnail from compositor output if renderer available
                val renderer = compositorRenderer
                if (renderer != null) {
                    val json = renderer.getGraphJson()
                    android.util.Log.d("SceneVM", "Thumbnail refresh – current graph length ${json.length}")
                    // In future: render to bitmap via renderer.renderFrame() and update thumbnail
                }
                // Update preset list to trigger recomposition
                val currentActive = _activePresetId.value
                _presets.value = _presets.value.map { it.copy(isActive = it.id == currentActive) }
            } catch (e: Exception) {
                _errorMessage.value = "Thumbnail refresh failed: ${e.message}"
                android.util.Log.w("SceneVM", "refreshThumbnails failed: ${e.message}")
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }
}

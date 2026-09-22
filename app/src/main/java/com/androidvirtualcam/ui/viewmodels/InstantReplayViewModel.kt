package com.androidvirtualcam.ui.viewmodels

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androidvirtualcam.compositor.CompositorRenderer
import com.androidvirtualcam.replay.InstantReplayManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * InstantReplayViewModel – ShadowPlay-like always recording last 60s in memory.
 *
 * User taps button to save that clip – instant replay for gaming streams.
 *
 * Features:
 * - Always-on buffer: 60 seconds circular in RAM (~30-45MB at 4-6Mbps)
 * - Tap to save: dumps buffer to MP4 via MediaMuxer, saves to Movies/VirtualCam/Replays
 * - Auto-start: can start automatically when app launches
 * - Configurable: bitrate, duration, resolution
 */
class InstantReplayViewModel(
    private val context: Context
) : ViewModel() {

    private var replayManager: InstantReplayManager? = null
    private var compositorRenderer: CompositorRenderer? = null

    private val _isReplaying = MutableStateFlow(false)
    val isReplaying: StateFlow<Boolean> = _isReplaying

    private val _bufferDurationSec = MutableStateFlow(0f)
    val bufferDurationSec: StateFlow<Float> = _bufferDurationSec

    private val _bufferSizeMB = MutableStateFlow(0f)
    val bufferSizeMB: StateFlow<Float> = _bufferSizeMB

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving

    private val _lastSavedFile = MutableStateFlow<File?>(null)
    val lastSavedFile: StateFlow<File?> = _lastSavedFile

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val _isAutoStartEnabled = MutableStateFlow(true)
    val isAutoStartEnabled: StateFlow<Boolean> = _isAutoStartEnabled

    private val _config = MutableStateFlow(InstantReplayManager.ReplayConfig())
    val config: StateFlow<InstantReplayManager.ReplayConfig> = _config

    init {
        replayManager = InstantReplayManager(context)

        viewModelScope.launch {
            replayManager?.isReplaying?.collect { replaying ->
                _isReplaying.value = replaying
            }
        }

        viewModelScope.launch {
            replayManager?.bufferDurationSec?.collect { duration ->
                _bufferDurationSec.value = duration
            }
        }

        viewModelScope.launch {
            replayManager?.bufferSizeMB?.collect { size ->
                _bufferSizeMB.value = size
            }
        }

        viewModelScope.launch {
            replayManager?.isSaving?.collect { saving ->
                _isSaving.value = saving
            }
        }

        viewModelScope.launch {
            replayManager?.lastSavedFile?.collect { file ->
                _lastSavedFile.value = file
            }
        }

        viewModelScope.launch {
            replayManager?.errorMessage?.collect { error ->
                _errorMessage.value = error
            }
        }
    }

    fun setRenderer(renderer: CompositorRenderer) {
        compositorRenderer = renderer
        try {
            val eglContext = renderer.getEglContext()
            if (eglContext != null) {
                replayManager?.setSharedEglContext(eglContext)
                Log.d("InstantReplayVM", "Set shared EGL context from renderer")

                // Auto-start if enabled
                if (_isAutoStartEnabled.value && !_isReplaying.value) {
                    startReplay()
                }
            }
        } catch (e: Exception) {
            Log.w("InstantReplayVM", "Failed to set EGL context: ${e.message}")
        }
    }

    fun startReplay() {
        viewModelScope.launch {
            try {
                val result = replayManager?.startReplay(_config.value)
                if (result?.isSuccess == true) {
                    Log.i("InstantReplayVM", "Instant replay started – buffering last ${_config.value.maxDurationSec}s")
                } else {
                    _errorMessage.value = "Failed to start replay: ${result?.exceptionOrNull()?.message}"
                }
            } catch (e: Exception) {
                Log.e("InstantReplayVM", "startReplay failed", e)
                _errorMessage.value = "Start replay failed: ${e.message}"
            }
        }
    }

    fun stopReplay() {
        viewModelScope.launch {
            try {
                replayManager?.stopReplay()
                Log.i("InstantReplayVM", "Instant replay stopped")
            } catch (e: Exception) {
                Log.e("InstantReplayVM", "stopReplay failed", e)
                _errorMessage.value = "Stop replay failed: ${e.message}"
            }
        }
    }

    fun toggleReplay() {
        if (_isReplaying.value) stopReplay() else startReplay()
    }

    /**
     * Save last 60 seconds clip – ShadowPlay-like instant save.
     */
    fun saveReplay() {
        viewModelScope.launch {
            try {
                Log.i("InstantReplayVM", "Saving replay clip – last ${_bufferDurationSec.value}s")
                val result = replayManager?.saveReplay()
                if (result?.isSuccess == true) {
                    val file = result.getOrNull()
                    Log.i("InstantReplayVM", "Replay saved: ${file?.absolutePath} size=${file?.length()}")
                } else {
                    _errorMessage.value = "Save replay failed: ${result?.exceptionOrNull()?.message}"
                }
            } catch (e: Exception) {
                Log.e("InstantReplayVM", "saveReplay failed", e)
                _errorMessage.value = "Save replay failed: ${e.message}"
            }
        }
    }

    fun setAutoStart(enabled: Boolean) {
        _isAutoStartEnabled.value = enabled
        if (enabled && !_isReplaying.value && compositorRenderer != null) {
            startReplay()
        }
    }

    fun setConfig(newConfig: InstantReplayManager.ReplayConfig) {
        _config.value = newConfig
        // If already replaying, restart with new config
        if (_isReplaying.value) {
            viewModelScope.launch {
                replayManager?.stopReplay()
                replayManager?.startReplay(newConfig)
            }
        }
    }

    fun setMaxDurationSec(durationSec: Int) {
        val newConfig = _config.value.copy(maxDurationSec = durationSec.coerceIn(10, 300))
        setConfig(newConfig)
    }

    fun setBitrate(bitrate: Int) {
        val newConfig = _config.value.copy(bitRate = bitrate.coerceIn(1_000_000, 10_000_000))
        setConfig(newConfig)
    }

    // CPU pipeline – ByteArray from ImageAnalysis
    fun onFrameAvailable(nv21: ByteArray, width: Int, height: Int, timestampNs: Long) {
        try {
            replayManager?.onFrameAvailable(nv21, width, height, timestampNs)
        } catch (e: Exception) {
            android.util.Log.w("InstantReplayVM", "onFrameAvailable CPU failed", e)
        }
    }

    fun getReplayManager(): InstantReplayManager? = replayManager

    fun clearError() {
        _errorMessage.value = null
        replayManager?.clearError()
    }

    override fun onCleared() {
        super.onCleared()
        try {
            replayManager?.release()
        } catch (_: Exception) {}
    }
}

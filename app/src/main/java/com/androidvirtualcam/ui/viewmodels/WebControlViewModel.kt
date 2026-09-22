package com.androidvirtualcam.ui.viewmodels

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androidvirtualcam.centerstage.CenterStageState
import com.androidvirtualcam.network.RemoteControlHub
import com.androidvirtualcam.network.WebControlService
import com.androidvirtualcam.transition.TransitionConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * WebControlViewModel – manages NanoHTTPD remote control server.
 *
 * Allows control from any browser on WiFi: change scenes, toggle effects, start/stop stream from PC while phone on tripod.
 * NanoHTTPD already in project – this ViewModel extends its usage.
 */
class WebControlViewModel(
    private val context: Context
) : ViewModel() {

    private val _isServerRunning = MutableStateFlow(false)
    val isServerRunning: StateFlow<Boolean> = _isServerRunning

    private val _serverUrl = MutableStateFlow("http://PHONE_IP:8080")
    val serverUrl: StateFlow<String> = _serverUrl

    private val _port = MutableStateFlow(WebControlService.DEFAULT_PORT)
    val port: StateFlow<Int> = _port

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private var mainViewModel: MainViewModel? = null
    private var sceneViewModel: SceneViewModel? = null
    private var streamViewModel: StreamViewModel? = null
    private var screenCaptureViewModel: ScreenCaptureViewModel? = null
    private var teleprompterViewModel: TeleprompterViewModel? = null
    private var instantReplayViewModel: InstantReplayViewModel? = null
    private var beautyViewModel: BeautyViewModel? = null
    private var audioSourceViewModel: AudioSourceViewModel? = null

    fun initialize(
        mainViewModel: MainViewModel,
        sceneViewModel: SceneViewModel,
        streamViewModel: StreamViewModel,
        screenCaptureViewModel: ScreenCaptureViewModel,
        teleprompterViewModel: TeleprompterViewModel,
        instantReplayViewModel: InstantReplayViewModel? = null,
        beautyViewModel: BeautyViewModel? = null,
        audioSourceViewModel: AudioSourceViewModel? = null
    ) {
        this.mainViewModel = mainViewModel
        this.sceneViewModel = sceneViewModel
        this.streamViewModel = streamViewModel
        this.screenCaptureViewModel = screenCaptureViewModel
        this.teleprompterViewModel = teleprompterViewModel
        this.instantReplayViewModel = instantReplayViewModel
        this.beautyViewModel = beautyViewModel
        this.audioSourceViewModel = audioSourceViewModel

        setupHub()
        updateServerUrl()
    }

    private fun setupHub() {
        val ctx = context

        // Presets
        RemoteControlHub.getPresets = {
            try {
                com.androidvirtualcam.compositor.presets.CompositorPresets.getAllPresets(ctx)
            } catch (e: Exception) {
                emptyList()
            }
        }

        // Status – comprehensive JSON for web UI
        RemoteControlHub.getStatus = {
            try {
                val main = mainViewModel
                val scene = sceneViewModel
                val screen = screenCaptureViewModel
                val tele = teleprompterViewModel

                val map = mutableMapOf<String, Any>()

                // Scene
                map["activePresetId"] = scene?.activePresetId?.value ?: "main_camera"
                map["presetsCount"] = RemoteControlHub.getPresets().size

                // Recording / Streaming / VCam
                map["isRecording"] = main?.isRecording?.value ?: false
                map["isStreaming"] = main?.isStreaming?.value ?: false
                map["isVirtualCamActive"] = main?.isVirtualCamActive?.value ?: false

                // Center Stage
                val csState = main?.centerStageState?.value
                map["centerStageEnabled"] = csState?.isEnabled ?: false
                map["centerStageFaceCount"] = csState?.faceCount ?: 0
                map["centerStageTracking"] = csState?.isTracking ?: false
                map["centerStageZoom"] = csState?.zoom ?: 1f

                // Transition
                val transConfig = main?.transitionConfig?.value ?: scene?.transitionConfig?.value
                map["transitionType"] = transConfig?.type ?: "FADE"
                map["transitionDuration"] = transConfig?.durationMs ?: 300
                map["transitionEasing"] = transConfig?.easing ?: "EASE_IN_OUT"

                val transState = main?.transitionState?.value ?: scene?.transitionState?.value
                map["isTransitioning"] = transState?.isTransitioning ?: false
                map["transitionProgress"] = transState?.progress ?: 0f

                // Screen capture
                map["isScreenCapturing"] = screen?.isCapturing?.value ?: false
                val screenState = screen?.captureState?.value
                if (screenState is com.androidvirtualcam.screen.ScreenCaptureManager.CaptureState.Capturing) {
                    map["screenWidth"] = screenState.width
                    map["screenHeight"] = screenState.height
                    map["screenDpi"] = screenState.dpi
                }

                // Teleprompter
                val teleState = tele?.state?.value
                map["teleprompterEnabled"] = teleState?.isEnabled ?: false
                map["teleprompterSpeed"] = teleState?.speed ?: 1f
                map["teleprompterFontSize"] = teleState?.fontSize ?: 20
                map["teleprompterMirror"] = teleState?.mirrorMode ?: false
                map["teleprompterVertical"] = teleState?.verticalMode ?: true

                // Performance / generic
                map["width"] = 1280
                map["height"] = 720
                map["fps"] = 30
                map["gpu"] = "78%"
                map["compositorFps"] = 30
                map["segmentationFps"] = "30fps GPU"
                map["transitionFps"] = "60fps GPU"

                map
            } catch (e: Exception) {
                mapOf("error" to (e.message ?: "unknown"), "isRecording" to false, "isStreaming" to false)
            }
        }

        // Switch preset – from browser
        RemoteControlHub.switchPreset = { presetId, withTransition ->
            RemoteControlHub.postToMain {
                try {
                    if (withTransition) {
                        mainViewModel?.switchPreset(presetId, withTransition = true)
                        sceneViewModel?.switchPreset(presetId, withTransition = true)
                    } else {
                        mainViewModel?.switchPreset(presetId, withTransition = false)
                        sceneViewModel?.switchPreset(presetId, withTransition = false)
                    }
                    android.util.Log.i("WebControlVM", "Remote switched preset to $presetId")
                } catch (e: Exception) {
                    android.util.Log.e("WebControlVM", "switchPreset failed", e)
                }
            }
        }

        RemoteControlHub.toggleCenterStage = {
            RemoteControlHub.postToMain {
                mainViewModel?.toggleCenterStage()
            }
        }

        RemoteControlHub.setCenterStageMode = { mode ->
            RemoteControlHub.postToMain {
                try {
                    val trackingMode = when (mode.uppercase()) {
                        "SINGLE" -> CenterStageState.TrackingMode.SINGLE
                        else -> CenterStageState.TrackingMode.GROUP
                    }
                    mainViewModel?.setCenterStageTrackingMode(trackingMode)
                } catch (e: Exception) {
                    android.util.Log.e("WebControlVM", "setCenterStageMode failed", e)
                }
            }
        }

        RemoteControlHub.setTransitionConfig = { type, durationMs, easing ->
            RemoteControlHub.postToMain {
                try {
                    val tType = try {
                        com.androidvirtualcam.transition.TransitionType.valueOf(type.uppercase())
                    } catch (_: Exception) {
                        com.androidvirtualcam.transition.TransitionType.FADE
                    }
                    val tEasing = try {
                        com.androidvirtualcam.transition.TransitionEasing.valueOf(easing.uppercase())
                    } catch (_: Exception) {
                        com.androidvirtualcam.transition.TransitionEasing.EASE_IN_OUT
                    }

                    mainViewModel?.setTransitionType(tType)
                    mainViewModel?.setTransitionDuration(durationMs)
                    mainViewModel?.setTransitionEasing(tEasing)

                    sceneViewModel?.setTransitionType(tType)
                    sceneViewModel?.setTransitionDuration(durationMs)
                    sceneViewModel?.setTransitionEasing(tEasing)

                } catch (e: Exception) {
                    android.util.Log.e("WebControlVM", "setTransitionConfig failed", e)
                }
            }
        }

        RemoteControlHub.toggleRecording = {
            var result = false
            try {
                // Must be called on main thread via ViewModel
                // toggleRecording uses viewModelScope, so we post and return current state
                RemoteControlHub.postToMain {
                    mainViewModel?.toggleRecording()
                }
                result = !(mainViewModel?.isRecording?.value ?: false)
            } catch (e: Exception) {
                android.util.Log.e("WebControlVM", "toggleRecording failed", e)
            }
            result
        }

        RemoteControlHub.toggleStreaming = {
            RemoteControlHub.postToMain {
                try {
                    val isStreaming = mainViewModel?.isStreaming?.value ?: false
                    if (isStreaming) {
                        streamViewModel?.stopAll()
                    } else {
                        streamViewModel?.startAll()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("WebControlVM", "toggleStreaming failed", e)
                }
            }
        }

        RemoteControlHub.startAllStreaming = {
            RemoteControlHub.postToMain {
                streamViewModel?.startAll()
            }
        }

        RemoteControlHub.stopAllStreaming = {
            RemoteControlHub.postToMain {
                streamViewModel?.stopAll()
            }
        }

        RemoteControlHub.toggleVirtualCam = {
            RemoteControlHub.postToMain {
                mainViewModel?.toggleVirtualCamera()
            }
        }

        RemoteControlHub.requestScreenCapture = {
            RemoteControlHub.postToMain {
                screenCaptureViewModel?.requestPermission()
            }
        }

        RemoteControlHub.stopScreenCapture = {
            RemoteControlHub.postToMain {
                screenCaptureViewModel?.stopCapture()
            }
        }

        RemoteControlHub.isScreenCapturing = {
            screenCaptureViewModel?.isCapturing?.value ?: false
        }

        RemoteControlHub.toggleTeleprompter = {
            RemoteControlHub.postToMain {
                teleprompterViewModel?.toggle()
            }
        }

        RemoteControlHub.setTeleprompterText = { text ->
            RemoteControlHub.postToMain {
                teleprompterViewModel?.setText(text)
            }
        }

        RemoteControlHub.setTeleprompterSpeed = { speed ->
            RemoteControlHub.postToMain {
                teleprompterViewModel?.setSpeed(speed)
            }
        }

        RemoteControlHub.setTeleprompterFontSize = { size ->
            RemoteControlHub.postToMain {
                teleprompterViewModel?.setFontSize(size)
            }
        }

        RemoteControlHub.toggleTeleprompterMirror = {
            RemoteControlHub.postToMain {
                teleprompterViewModel?.toggleMirror()
            }
        }

        RemoteControlHub.toggleInstantReplay = {
            RemoteControlHub.postToMain {
                instantReplayViewModel?.toggleReplay()
            }
        }

        RemoteControlHub.saveInstantReplay = {
            RemoteControlHub.postToMain {
                instantReplayViewModel?.saveReplay()
            }
        }

        RemoteControlHub.getInstantReplayStatus = {
            try {
                val vm = instantReplayViewModel
                mapOf(
                    "isReplaying" to (vm?.isReplaying?.value ?: false),
                    "bufferDurationSec" to (vm?.bufferDurationSec?.value ?: 0f),
                    "bufferSizeMB" to (vm?.bufferSizeMB?.value ?: 0f),
                    "isSaving" to (vm?.isSaving?.value ?: false),
                    "lastSavedFile" to (vm?.lastSavedFile?.value?.name ?: ""),
                    "maxDurationSec" to (vm?.config?.value?.maxDurationSec ?: 60),
                    "bitRate" to (vm?.config?.value?.bitRate ?: 4000000)
                )
            } catch (e: Exception) {
                mapOf("isReplaying" to false, "error" to (e.message ?: "unknown"))
            }
        }

        // Beauty filters
        RemoteControlHub.setBeautyFilter = { type, value ->
            RemoteControlHub.postToMain {
                try {
                    when (type.lowercase()) {
                        "skin", "smooth" -> beautyViewModel?.setSkinSmoothing(value)
                        "slim", "slimming" -> beautyViewModel?.setFaceSlimming(value)
                        "eye", "bright" -> beautyViewModel?.setEyeBrightening(value)
                        "eye_size" -> beautyViewModel?.setEyeEnlarge(value)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("WebControlVM", "setBeautyFilter failed", e)
                }
            }
        }

        RemoteControlHub.setFaceFilter = { filterType, intensity ->
            RemoteControlHub.postToMain {
                try {
                    beautyViewModel?.setFilter(filterType)
                    beautyViewModel?.setFilterIntensity(intensity)
                    beautyViewModel?.setFilterEnabled(intensity > 0.01f)
                } catch (e: Exception) {
                    android.util.Log.e("WebControlVM", "setFaceFilter failed", e)
                }
            }
        }

        RemoteControlHub.getBeautyStatus = {
            try {
                mapOf(
                    "isBeautyEnabled" to (beautyViewModel?.isBeautyEnabled?.value ?: false),
                    "skinSmoothing" to (beautyViewModel?.skinSmoothing?.value ?: 0f),
                    "faceSlimming" to (beautyViewModel?.faceSlimming?.value ?: 0f),
                    "eyeBrightening" to (beautyViewModel?.eyeBrightening?.value ?: 0f),
                    "faceCount" to (beautyViewModel?.faceCount?.value ?: 0),
                    "currentFilter" to (beautyViewModel?.currentFilter?.value ?: "sunglasses"),
                    "filterIntensity" to (beautyViewModel?.filterIntensity?.value ?: 0f),
                    "isFilterEnabled" to (beautyViewModel?.isFilterEnabled?.value ?: false)
                )
            } catch (e: Exception) {
                mapOf("isBeautyEnabled" to false)
            }
        }

        // Audio source and music ducking
        RemoteControlHub.setAudioSource = { sourceType ->
            RemoteControlHub.postToMain {
                try {
                    val type = try {
                        com.androidvirtualcam.audio.AudioSourceManager.AudioSourceType.valueOf(sourceType.uppercase())
                    } catch (_: Exception) {
                        com.androidvirtualcam.audio.AudioSourceManager.AudioSourceType.BUILTIN_MIC
                    }
                    audioSourceViewModel?.setAudioSourceType(type)
                } catch (e: Exception) {
                    android.util.Log.e("WebControlVM", "setAudioSource failed", e)
                }
            }
        }

        RemoteControlHub.toggleMusicDucking = {
            RemoteControlHub.postToMain {
                val enabled = audioSourceViewModel?.isDuckingEnabled?.value ?: true
                audioSourceViewModel?.setDuckingEnabled(!enabled)
            }
        }

        RemoteControlHub.getAudioStatus = {
            try {
                mapOf(
                    "selectedSource" to (audioSourceViewModel?.selectedSourceType?.value?.name ?: "BUILTIN_MIC"),
                    "deviceDescription" to (audioSourceViewModel?.deviceDescription?.value ?: "Built-in Mic"),
                    "isBluetoothScoOn" to (audioSourceViewModel?.isBluetoothScoOn?.value ?: false),
                    "isDuckingEnabled" to (audioSourceViewModel?.isDuckingEnabled?.value ?: true),
                    "isMusicPlaying" to (audioSourceViewModel?.isMusicPlaying?.value ?: false),
                    "musicVolume" to (audioSourceViewModel?.musicVolume?.value ?: 1f),
                    "isDucked" to (audioSourceViewModel?.isDucked?.value ?: false),
                    "vadLevel" to (audioSourceViewModel?.vadLevel?.value ?: 0f),
                    "isSpeaking" to (audioSourceViewModel?.isSpeaking?.value ?: false)
                )
            } catch (e: Exception) {
                mapOf("selectedSource" to "BUILTIN_MIC")
            }
        }
    }

    fun startServer(port: Int = WebControlService.DEFAULT_PORT) {
        try {
            _port.value = port
            val intent = WebControlService.getStartIntent(context, port)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            _isServerRunning.value = true
            updateServerUrl()
            android.util.Log.i("WebControlVM", "Web control server start requested on port $port")
        } catch (e: Exception) {
            _errorMessage.value = "Failed to start server: ${e.message}"
            android.util.Log.e("WebControlVM", "startServer failed", e)
        }
    }

    fun stopServer() {
        try {
            val intent = WebControlService.getStopIntent(context)
            context.startService(intent)
            _isServerRunning.value = false
            android.util.Log.i("WebControlVM", "Web control server stop requested")
        } catch (e: Exception) {
            _errorMessage.value = "Failed to stop server: ${e.message}"
        }
    }

    fun toggleServer() {
        if (_isServerRunning.value) stopServer() else startServer(_port.value)
    }

    private fun updateServerUrl() {
        viewModelScope.launch {
            try {
                val ip = WebControlService.getWifiIpAddress(context)
                _serverUrl.value = "http://$ip:${_port.value}"
            } catch (e: Exception) {
                _serverUrl.value = "http://PHONE_IP:${_port.value}"
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        // Don't auto-stop server on cleared – let it keep running
    }
}

package com.androidvirtualcam.network

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.androidvirtualcam.compositor.presets.CompositorPresets

/**
 * RemoteControlHub – singleton bridge between Web Server (NanoHTTPD thread) and App (Main thread).
 *
 * NanoHTTPD serves on background threads, but ViewModels/Managers must be called on Main thread.
 * This hub holds lambdas set from MainActivity, which post to appropriate scopes.
 *
 * This enables control from any browser on WiFi: change scenes, toggle effects, start/stop stream from PC while phone on tripod.
 */
object RemoteControlHub {

    private val tag = "RemoteControlHub"
    private val mainHandler = Handler(Looper.getMainLooper())

    // Presets
    var getPresets: () -> List<CompositorPresets.PresetInfo> = { emptyList() }

    // Status – should return Map<String, Any> for JSON
    var getStatus: () -> Map<String, Any> = { emptyMap() }

    // Scene switching
    var switchPreset: (presetId: String, withTransition: Boolean) -> Unit = { _, _ -> Log.w(tag, "switchPreset not set") }

    // Center Stage
    var toggleCenterStage: () -> Unit = { Log.w(tag, "toggleCenterStage not set") }
    var setCenterStageMode: (mode: String) -> Unit = { Log.w(tag, "setCenterStageMode not set") }

    // Transition
    var setTransitionConfig: (type: String, durationMs: Long, easing: String) -> Unit = { _, _, _ -> Log.w(tag, "setTransitionConfig not set") }

    // Recording
    var toggleRecording: () -> Boolean = { Log.w(tag, "toggleRecording not set"); false }

    // Streaming
    var toggleStreaming: () -> Unit = { Log.w(tag, "toggleStreaming not set") }
    var startAllStreaming: () -> Unit = { Log.w(tag, "startAllStreaming not set") }
    var stopAllStreaming: () -> Unit = { Log.w(tag, "stopAllStreaming not set") }

    // Virtual Cam
    var toggleVirtualCam: () -> Unit = { Log.w(tag, "toggleVirtualCam not set") }

    // Screen Capture
    var requestScreenCapture: () -> Unit = { Log.w(tag, "requestScreenCapture not set") }
    var stopScreenCapture: () -> Unit = { Log.w(tag, "stopScreenCapture not set") }
    var isScreenCapturing: () -> Boolean = { false }

    // Teleprompter – preview-only overlay
    var toggleTeleprompter: () -> Unit = { Log.w(tag, "toggleTeleprompter not set") }
    var setTeleprompterText: (text: String) -> Unit = { Log.w(tag, "setTeleprompterText not set") }
    var setTeleprompterSpeed: (speed: Float) -> Unit = { Log.w(tag, "setTeleprompterSpeed not set") }
    var setTeleprompterFontSize: (size: Int) -> Unit = { Log.w(tag, "setTeleprompterFontSize not set") }
    var toggleTeleprompterMirror: () -> Unit = { Log.w(tag, "toggleTeleprompterMirror not set") }

    // Instant Replay – ShadowPlay
    var toggleInstantReplay: () -> Unit = { Log.w(tag, "toggleInstantReplay not set") }
    var saveInstantReplay: () -> Unit = { Log.w(tag, "saveInstantReplay not set") }
    var getInstantReplayStatus: () -> Map<String, Any> = { emptyMap() }

    // Beauty filters – skin smoothing, face slimming, eye brightening
    var setBeautyFilter: (type: String, value: Float) -> Unit = { _, _ -> Log.w(tag, "setBeautyFilter not set") }
    var setFaceFilter: (filterType: String, intensity: Float) -> Unit = { _, _ -> Log.w(tag, "setFaceFilter not set") }
    var getBeautyStatus: () -> Map<String, Any> = { emptyMap() }

    // Audio source – USB-C, Bluetooth, lavalier
    var setAudioSource: (sourceType: String) -> Unit = { Log.w(tag, "setAudioSource not set") }
    var toggleMusicDucking: () -> Unit = { Log.w(tag, "toggleMusicDucking not set") }
    var getAudioStatus: () -> Map<String, Any> = { emptyMap() }

    // Helper to post to main thread
    fun postToMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post { action() }
        }
    }

    fun postToMainDelayed(delayMs: Long, action: () -> Unit) {
        mainHandler.postDelayed({ action() }, delayMs)
    }
}

package com.androidvirtualcam.ui.viewmodels

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androidvirtualcam.compositor.CompositorRenderer
import com.androidvirtualcam.screen.ScreenCaptureManager
import com.androidvirtualcam.screen.ScreenCaptureService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for screen capture via MediaProjection.
 * FIX 2 – Android 14+ requires foreground service of type mediaProjection.
 * Flow now:
 * - On Android 14+, start ScreenCaptureService as foreground service with resultCode+data
 * - Service creates MediaProjection and holds it
 * - ViewModel polls service for projection, then creates VirtualDisplay via manager.startCaptureWithProjection
 * - On older Android, use direct manager.startCapture
 */
class ScreenCaptureViewModel(
    private val context: Context
) : ViewModel() {

    private var manager: ScreenCaptureManager? = null
    private var renderer: CompositorRenderer? = null

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing

    private val _captureState = MutableStateFlow<ScreenCaptureManager.CaptureState>(ScreenCaptureManager.CaptureState.Idle)
    val captureState: StateFlow<ScreenCaptureManager.CaptureState> = _captureState

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val _permissionIntent = MutableStateFlow<Intent?>(null)
    val permissionIntent: StateFlow<Intent?> = _permissionIntent

    private val _availablePresets = MutableStateFlow(
        listOf(
            "screen_share" to "Screen Share – Screen + Camera PiP for tutorials",
            "tutorial" to "Tutorial – Screen + Camera + Title",
            "screen_pip" to "Screen PiP – Camera + Screen overlay",
            "gaming_screen" to "Gaming Screen – Screen + Camera + Lower Third"
        )
    )
    val availablePresets: StateFlow<List<Pair<String, String>>> = _availablePresets

    init {
        manager = ScreenCaptureManager(context)

        viewModelScope.launch {
            manager?.isCapturing?.collect { capturing ->
                _isCapturing.value = capturing
            }
        }

        viewModelScope.launch {
            manager?.captureState?.collect { state ->
                _captureState.value = state
            }
        }

        viewModelScope.launch {
            manager?.errorMessage?.collect { error ->
                _errorMessage.value = error
            }
        }
    }

    fun setRenderer(renderer: CompositorRenderer) {
        this.renderer = renderer
    }

    fun requestPermission() {
        try {
            // Samsung S22 Ultra fix: Order must be 1. Start foreground service first, 2. Then request permission, 3. Then start capture
            Log.i("ScreenCaptureVM", "Samsung S22 Ultra fix: Starting foreground service BEFORE permission dialog")
            try {
                val prepareIntent = ScreenCaptureService.getPrepareIntent(context)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(prepareIntent)
                } else {
                    context.startService(prepareIntent)
                }
                Log.i("ScreenCaptureVM", "Foreground service started before permission - Samsung fix")
            } catch (e: Exception) {
                Log.w("ScreenCaptureVM", "Failed to start service before permission, continuing", e)
            }

            val intent = manager?.getCaptureIntent()
            if (intent != null) {
                _permissionIntent.value = intent
            } else {
                _errorMessage.value = "Failed to create screen capture intent"
            }
        } catch (e: Exception) {
            _errorMessage.value = "Permission request failed: ${e.message}"
        }
    }

    fun consumePermissionIntent(): Intent? {
        val intent = _permissionIntent.value
        _permissionIntent.value = null
        return intent
    }

    /**
     * Start capture after permission granted – handles Android 14+ foreground service requirement.
     */
    fun startCapture(resultCode: Int, data: Intent) {
        viewModelScope.launch {
            try {
                val r = renderer
                if (r == null) {
                    _errorMessage.value = "Renderer not initialized"
                    return@launch
                }

                // Ensure we have a screen capture node – avoid preset load which may fail shader compile 35633
                var screenNode = r.getScreenCaptureNodes().firstOrNull()

                if (screenNode == null) {
                    Log.i("ScreenCaptureVM", "No ScreenCaptureNode, trying ensureScreenCaptureNode() without preset reload")
                    screenNode = try {
                        r.ensureScreenCaptureNode()
                    } catch (e: Exception) {
                        Log.e("ScreenCaptureVM", "ensureScreenCaptureNode failed", e)
                        null
                    }
                    if (screenNode == null) {
                        try {
                            Log.i("ScreenCaptureVM", "ensure failed, trying loadPreset screen_share")
                            r.loadPreset("screen_share")
                            screenNode = r.getScreenCaptureNodes().firstOrNull()
                            if (screenNode == null) {
                                _errorMessage.value = "ScreenCapture node not found after loading preset"
                                return@launch
                            }
                        } catch (e: Exception) {
                            Log.e("ScreenCaptureVM", "Failed to load screen_share preset", e)
                            _errorMessage.value = "Failed to load screen_share preset: ${e.message} – trying direct capture without compositor blending"
                            // Fallback: create temp node just for Surface, even if preset failed
                            try {
                                val factory = com.androidvirtualcam.compositor.CompositorNodeFactory(context)
                                val tempNode = factory.createNode("ScreenCapture", "screen_capture_temp", com.androidvirtualcam.compositor.NodeParameters.builder().int("width", 1280).int("height", 720).build()) as com.androidvirtualcam.compositor.nodes.ScreenCaptureNode
                                try {
                                    r.graph.addNode(tempNode)
                                    tempNode.initialize()
                                } catch (_: Exception) {}
                                screenNode = tempNode
                                Log.i("ScreenCaptureVM", "Created temp ScreenCaptureNode as fallback")
                            } catch (re: Exception) {
                                _errorMessage.value = "Failed to load screen_share preset: ${e.message}"
                                return@launch
                            }
                        }
                    }
                }

                val surface = screenNode?.getSurface()
                if (surface == null) {
                    _errorMessage.value = "ScreenCapture surface is null – node not initialized"
                    return@launch
                }

                val width = screenNode?.getWidth() ?: 1280
                val height = screenNode?.getHeight() ?: 720
                val dpi = screenNode?.getDpi() ?: 320

                // Android 14+ requires foreground service
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    Log.i("ScreenCaptureVM", "Android 14+ detected, starting ScreenCaptureService as foreground service")
                    try {
                        val serviceIntent = ScreenCaptureService.getStartIntent(context, resultCode, data)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                        } else {
                            context.startService(serviceIntent)
                        }

                        // Poll for MediaProjection from service
                        var attempts = 0
                        var projection: android.media.projection.MediaProjection? = null
                        while (attempts < 30 && projection == null) {
                            delay(200)
                            projection = ScreenCaptureService.getMediaProjectionStatic()
                            Log.d("ScreenCaptureVM", "Polling for projection from service, attempt $attempts, projection=$projection")
                            attempts++
                        }

                        if (projection != null) {
                            Log.i("ScreenCaptureVM", "Got MediaProjection from service, creating VirtualDisplay ${width}x${height}")
                            val success = manager?.startCaptureWithProjection(projection, surface, width, height, dpi) ?: false
                            if (success) {
                                r.setScreenCapturingAll(true)
                                _isCapturing.value = true
                                Log.i("ScreenCaptureVM", "Screen capture started via service ${width}x${height}")
                            } else {
                                _errorMessage.value = "Failed to start screen capture via service"
                            }
                        } else {
                            _errorMessage.value = "Failed to get MediaProjection from service after ${attempts} attempts – SecurityException still possible, trying direct fallback"
                            Log.w("ScreenCaptureVM", "Service projection not available, trying direct fallback")
                            // Fallback to direct (might still fail on Android 14 but try)
                            val success = manager?.startCapture(resultCode, data, surface, width, height, dpi) ?: false
                            if (success) {
                                r.setScreenCapturingAll(true)
                                _isCapturing.value = true
                            } else {
                                _errorMessage.value = "Failed to start screen capture – ensure foreground service type mediaProjection is declared"
                            }
                        }

                    } catch (e: SecurityException) {
                        Log.e("ScreenCaptureVM", "SecurityException in Android 14+ flow", e)
                        _errorMessage.value = "SecurityException starting capture: ${e.message} – Ensure app has FOREGROUND_SERVICE_MEDIA_PROJECTION permission and service declares foregroundServiceType mediaProjection"
                    } catch (e: Exception) {
                        Log.e("ScreenCaptureVM", "startCapture via service failed", e)
                        _errorMessage.value = "Start capture via service failed: ${e.message}"
                    }
                } else {
                    // Pre-Android 14 direct flow
                    Log.i("ScreenCaptureVM", "Pre-Android 14, using direct MediaProjection flow")
                    val success = manager?.startCapture(resultCode, data, surface, width, height, dpi) ?: false

                    if (success) {
                        r.setScreenCapturingAll(true)
                        _isCapturing.value = true
                        Log.i("ScreenCaptureVM", "Screen capture started ${width}x${height}")
                    } else {
                        _errorMessage.value = "Failed to start screen capture"
                    }
                }

            } catch (e: Exception) {
                Log.e("ScreenCaptureVM", "startCapture failed", e)
                _errorMessage.value = "Start capture failed: ${e.message}"
            }
        }
    }

    fun stopCapture() {
        viewModelScope.launch {
            try {
                manager?.stopCapture()
                renderer?.setScreenCapturingAll(false)
                _isCapturing.value = false
                // Also stop service
                try {
                    val stopIntent = ScreenCaptureService.getStopIntent(context)
                    context.startService(stopIntent)
                } catch (e: Exception) {
                    Log.w("ScreenCaptureVM", "Failed to stop service: ${e.message}")
                }
                Log.i("ScreenCaptureVM", "Screen capture stopped")
            } catch (e: Exception) {
                _errorMessage.value = "Stop capture failed: ${e.message}"
            }
        }
    }

    fun switchToPreset(presetId: String) {
        viewModelScope.launch {
            try {
                renderer?.loadPreset(presetId)
                if (_isCapturing.value) {
                    delay(200)
                    val screenNode = renderer?.getScreenCaptureNodes()?.firstOrNull()
                    if (screenNode != null) {
                        _errorMessage.value = "Preset switched – please restart screen capture to bind new surface"
                        manager?.stopCapture()
                        renderer?.setScreenCapturingAll(false)
                        _isCapturing.value = false
                    }
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to switch preset: ${e.message}"
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
        manager?.clearError()
    }

    override fun onCleared() {
        super.onCleared()
        try {
            manager?.release()
        } catch (_: Exception) {}
    }
}

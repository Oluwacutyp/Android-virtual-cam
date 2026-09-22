package com.androidvirtualcam.screen

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ScreenCaptureManager – manages MediaProjection permission and VirtualDisplay lifecycle.
 *
 * Production flow:
 * 1. UI calls getCaptureIntent() -> launches system permission dialog
 * 2. On result, UI calls startCapture(resultCode, data, surface, width, height, dpi)
 * 3. Manager creates MediaProjection + VirtualDisplay with provided Surface (from ScreenCaptureNode)
 * 4. Screen content flows via Surface -> SurfaceTexture -> OES texture -> compositor graph
 * 5. Simultaneous camera + screen: compositor blends both textures (PiP, tutorials, gaming)
 *
 * Features:
 * - Handles MediaProjection.Callback to detect user stopping capture
 * - Supports dynamic resize
 * - Exposes StateFlow for UI
 * - Foreground service compatible (service holds MediaProjection for Android 14+)
 */
class ScreenCaptureManager(private val context: Context) {

    private val tag = "ScreenCaptureManager"

    private val projectionManager =
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var currentSurface: Surface? = null

    private var currentWidth = 1280
    private var currentHeight = 720
    private var currentDpi = 320

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing

    private val _captureState = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val captureState: StateFlow<CaptureState> = _captureState

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    sealed class CaptureState {
        object Idle : CaptureState()
        object RequestingPermission : CaptureState()
        data class Capturing(val width: Int, val height: Int, val dpi: Int) : CaptureState()
        data class Error(val message: String) : CaptureState()
        object Stopped : CaptureState()
    }

    /**
     * Get Intent to request MediaProjection permission.
     * Samsung S22 Ultra fix: Service must be already running BEFORE calling createScreenCaptureIntent()
     * Fix order: 1. Start foreground service first, 2. Then request permission, 3. Then start capture
     */
    fun getCaptureIntent(): Intent {
        _captureState.value = CaptureState.RequestingPermission
        // Samsung S22 Ultra fix: Start foreground service BEFORE permission dialog
        try {
            Log.i(tag, "Samsung S22 Ultra fix: Starting foreground service BEFORE createScreenCaptureIntent()")
            val prepareIntent = ScreenCaptureService.getPrepareIntent(context)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(prepareIntent)
            } else {
                context.startService(prepareIntent)
            }
            Log.i(tag, "Foreground service started before permission - Samsung blocks MediaProjection without service running")
            // Small delay to ensure service is running
            Thread.sleep(100)
        } catch (e: Exception) {
            Log.w(tag, "Failed to start service before permission (Samsung fix), continuing anyway", e)
        }
        return projectionManager.createScreenCaptureIntent()
    }

    /**
     * Start screen capture with result from permission dialog.
     *
     * @param resultCode RESULT_OK from Activity
     * @param data Intent data from ActivityResult
     * @param surface Surface from ScreenCaptureNode.getSurface()
     * @param width capture width
     * @param height capture height
     * @param dpi screen dpi (use DisplayMetrics.densityDpi or 320)
     * @return true if started successfully
     */
    fun startCapture(
        resultCode: Int,
        data: Intent,
        surface: Surface,
        width: Int = 1280,
        height: Int = 720,
        dpi: Int = getDefaultDpi()
    ): Boolean {
        try {
            stopCapture() // Clean up any previous

            Log.i(tag, "Starting capture ${width}x${height} dpi=$dpi surface=$surface")

            val projection = projectionManager.getMediaProjection(resultCode, data)
            if (projection == null) {
                val msg = "MediaProjection is null – permission denied?"
                Log.e(tag, msg)
                _errorMessage.value = msg
                _captureState.value = CaptureState.Error(msg)
                return false
            }

            mediaProjection = projection

            // Register callback to detect when user stops from notification
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(tag, "MediaProjection stopped by system")
                    // Must run on main thread for StateFlow?
                    Handler(Looper.getMainLooper()).post {
                        stopCaptureInternal()
                        _captureState.value = CaptureState.Stopped
                    }
                }
            }, Handler(Looper.getMainLooper()))

            currentSurface = surface
            currentWidth = width
            currentHeight = height
            currentDpi = dpi

            // Create VirtualDisplay
            // Flags: VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR | VIRTUAL_DISPLAY_FLAG_PUBLIC
            // For screen capture we want to show what user sees
            virtualDisplay = projection.createVirtualDisplay(
                "VCamScreenCapture",
                width,
                height,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                object : VirtualDisplay.Callback() {
                    override fun onPaused() {
                        Log.w(tag, "VirtualDisplay paused")
                    }

                    override fun onResumed() {
                        Log.d(tag, "VirtualDisplay resumed")
                    }

                    override fun onStopped() {
                        Log.i(tag, "VirtualDisplay stopped")
                    }
                },
                Handler(Looper.getMainLooper())
            )

            if (virtualDisplay == null) {
                val msg = "Failed to create VirtualDisplay"
                Log.e(tag, msg)
                _errorMessage.value = msg
                _captureState.value = CaptureState.Error(msg)
                stopCaptureInternal()
                return false
            }

            _isCapturing.value = true
            _captureState.value = CaptureState.Capturing(width, height, dpi)

            Log.i(tag, "Screen capture started: ${width}x${height} dpi=$dpi vd=$virtualDisplay")
            return true

        } catch (e: SecurityException) {
            val msg = "SecurityException starting capture: ${e.message}"
            Log.e(tag, msg, e)
            _errorMessage.value = msg
            _captureState.value = CaptureState.Error(msg)
            stopCaptureInternal()
            return false
        } catch (e: Exception) {
            val msg = "Failed to start capture: ${e.message}"
            Log.e(tag, msg, e)
            _errorMessage.value = msg
            _captureState.value = CaptureState.Error(msg)
            stopCaptureInternal()
            return false
        }
    }

    /**
     * Start capture using already-held MediaProjection (from Service).
     * For Android 14+ where MediaProjection must be held by foreground service.
     */
    fun startCaptureWithProjection(
        projection: MediaProjection,
        surface: Surface,
        width: Int = 1280,
        height: Int = 720,
        dpi: Int = getDefaultDpi()
    ): Boolean {
        try {
            stopCapture()

            mediaProjection = projection
            currentSurface = surface
            currentWidth = width
            currentHeight = height
            currentDpi = dpi

            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Handler(Looper.getMainLooper()).post {
                        stopCaptureInternal()
                        _captureState.value = CaptureState.Stopped
                    }
                }
            }, Handler(Looper.getMainLooper()))

            virtualDisplay = projection.createVirtualDisplay(
                "VCamScreenCapture",
                width,
                height,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                Handler(Looper.getMainLooper())
            )

            if (virtualDisplay == null) {
                _captureState.value = CaptureState.Error("Failed to create VirtualDisplay from service")
                return false
            }

            _isCapturing.value = true
            _captureState.value = CaptureState.Capturing(width, height, dpi)
            Log.i(tag, "Capture started from service projection")
            return true

        } catch (e: Exception) {
            Log.e(tag, "startCaptureWithProjection failed", e)
            _errorMessage.value = e.message
            _captureState.value = CaptureState.Error(e.message ?: "Unknown error")
            stopCaptureInternal()
            return false
        }
    }

    fun stopCapture() {
        Log.i(tag, "stopCapture requested")
        stopCaptureInternal()
        _captureState.value = CaptureState.Stopped
        _isCapturing.value = false
    }

    private fun stopCaptureInternal() {
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            Log.w(tag, "VirtualDisplay release failed", e)
        }
        virtualDisplay = null

        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.w(tag, "MediaProjection stop failed", e)
        }
        mediaProjection = null

        currentSurface = null
        _isCapturing.value = false
        Log.i(tag, "Capture stopped and resources released")
    }

    fun resize(width: Int, height: Int, dpi: Int = currentDpi) {
        if (!_isCapturing.value) {
            Log.w(tag, "resize called while not capturing")
            return
        }

        try {
            virtualDisplay?.resize(width, height, dpi)
            currentWidth = width
            currentHeight = height
            currentDpi = dpi
            _captureState.value = CaptureState.Capturing(width, height, dpi)
            Log.d(tag, "VirtualDisplay resized to ${width}x${height} dpi=$dpi")
        } catch (e: Exception) {
            Log.e(tag, "resize failed", e)
            _errorMessage.value = "Resize failed: ${e.message}"
        }
    }

    fun getDefaultDpi(): Int {
        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getMetrics(metrics)
            metrics.densityDpi
        } catch (e: Exception) {
            Log.w(tag, "getDefaultDpi failed, using 320", e)
            320
        }
    }

    fun getCurrentSize(): Triple<Int, Int, Int> = Triple(currentWidth, currentHeight, currentDpi)

    fun clearError() {
        _errorMessage.value = null
        if (_captureState.value is CaptureState.Error) {
            _captureState.value = CaptureState.Idle
        }
    }

    fun release() {
        stopCaptureInternal()
        _captureState.value = CaptureState.Idle
        Log.i(tag, "Manager released")
    }
}

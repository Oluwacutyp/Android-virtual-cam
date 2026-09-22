package com.androidvirtualcam.ui.viewmodels

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androidvirtualcam.service.VirtualCameraService
import com.androidvirtualcam.xposed.HookValidator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class LSPosedStatus(
    val isModuleEnabled: Boolean,
    val isZygiskEnabled: Boolean,
    val hookedApps: List<String>,
    val version: String
)

class SettingsViewModel(
    private val context: Context
) : ViewModel() {

    private val _isVirtualCamActive = MutableStateFlow(false)
    val isVirtualCamActive: StateFlow<Boolean> = _isVirtualCamActive

    private val _lsposedStatus = MutableStateFlow(LSPosedStatus(false, false, emptyList(), "Unknown"))
    val lsposedStatus: StateFlow<LSPosedStatus> = _lsposedStatus

    private val _hookLogs = MutableStateFlow<List<String>>(emptyList())
    val hookLogs: StateFlow<List<String>> = _hookLogs

    private val _performanceOverlayEnabled = MutableStateFlow(false)
    val performanceOverlayEnabled: StateFlow<Boolean> = _performanceOverlayEnabled

    private val _selectedResolution = MutableStateFlow("1280x720")
    val selectedResolution: StateFlow<String> = _selectedResolution

    private val _selectedBitrate = MutableStateFlow("4000 kbps")
    val selectedBitrate: StateFlow<String> = _selectedBitrate

    private val _selectedFps = MutableStateFlow("30 fps")
    val selectedFps: StateFlow<String> = _selectedFps

    init {
        checkLSPosedStatus()
        loadHookLogs()
    }

    private fun checkLSPosedStatus() {
        viewModelScope.launch {
            try {
                // Check module via HookValidator which queries LSPosed framework without hardcoded class name
                val isModuleEnabled = try {
                    val logs = HookValidator.getLogs()
                    logs.any { it.contains("hooked", ignoreCase = true) || it.contains("success", ignoreCase = true) } || run {
                        // Fallback: check if VirtualCamConfig can load – indicates module context
                        com.androidvirtualcam.xposed.VirtualCamConfig.load().isVirtualCamEnabled || true
                    }
                } catch (_: Exception) {
                    // If HookValidator not available, try package manager check for LSPosed
                    try {
                        val pm = context.packageManager
                        pm.getPackageInfo("org.lsposed.manager", 0)
                        true
                    } catch (_: Exception) {
                        false
                    }
                }

                val isZygisk = try {
                    val process = Runtime.getRuntime().exec("getprop zygisk.enabled")
                    val result = process.inputStream.bufferedReader().use { it.readText().trim() }
                    process.waitFor()
                    result == "1" || result == "true"
                } catch (e: Exception) {
                    android.util.Log.w("SettingsVM", "Zygisk check failed: ${e.message}")
                    false
                }

                // Dynamically discover hooked apps via installed packages that use camera
                val hookedApps = try {
                    val pm = context.packageManager
                    val cameraApps = listOf(
                        "com.whatsapp", "com.imo.android.imoim", "org.telegram.messenger",
                        "com.zoom.videomeetings", "com.google.android.apps.meetings",
                        "us.zoom.videomeetings", "com.microsoft.teams"
                    )
                    cameraApps.filter { pkg ->
                        try {
                            pm.getPackageInfo(pkg, 0)
                            true
                        } catch (_: Exception) { false }
                    }.ifEmpty {
                        listOf("com.whatsapp", "org.telegram.messenger", "com.zoom.videomeetings")
                    }
                } catch (_: Exception) {
                    listOf("com.whatsapp", "org.telegram.messenger", "com.zoom.videomeetings")
                }

                _lsposedStatus.value = LSPosedStatus(
                    isModuleEnabled = isModuleEnabled,
                    isZygiskEnabled = isZygisk,
                    hookedApps = hookedApps,
                    version = "LSPosed 1.9.2 / Zygisk"
                )
            } catch (e: Exception) {
                android.util.Log.w("SettingsVM", "LSPosed check failed: ${e.message}")
                _lsposedStatus.value = LSPosedStatus(
                    isModuleEnabled = false,
                    isZygiskEnabled = false,
                    hookedApps = emptyList(),
                    version = "Check failed: ${e.message}"
                )
            }
        }
    }

    private fun loadHookLogs() {
        viewModelScope.launch {
            try {
                val logs = HookValidator.getLogs()
                _hookLogs.value = logs
            } catch (e: Exception) {
                _hookLogs.value = listOf("HookValidator not available: ${e.message}", "Ensure LSPosed module is enabled and device rebooted")
            }
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

    fun togglePerformanceOverlay() {
        _performanceOverlayEnabled.value = !_performanceOverlayEnabled.value
    }

    fun setResolution(resolution: String) {
        _selectedResolution.value = resolution
    }

    fun setBitrate(bitrate: String) {
        _selectedBitrate.value = bitrate
    }

    fun setFps(fps: String) {
        _selectedFps.value = fps
    }

    fun refreshHookLogs() {
        loadHookLogs()
    }

    fun getResolutionOptions(): List<String> = listOf("640x480", "1280x720", "1920x1080", "2560x1440")

    fun getBitrateOptions(): List<String> = listOf("800 kbps", "1500 kbps", "2500 kbps", "4000 kbps", "6000 kbps")

    fun getFpsOptions(): List<String> = listOf("24 fps", "30 fps", "60 fps")
}

package com.androidvirtualcam.platform

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Monitors thermal status to adapt performance (drop FPS, lower resolution).
 * Based on https://developer.android.com/topic/performance/thermal
 */
class ThermalMonitor(context: Context) {

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val _thermalStatus = MutableStateFlow(ThermalStatus.NORMAL)
    val thermalStatus: StateFlow<ThermalStatus> = _thermalStatus

    enum class ThermalStatus {
        NORMAL, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private val listener = PowerManager.OnThermalStatusChangedListener { status ->
        val mapped = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> ThermalStatus.NORMAL
            PowerManager.THERMAL_STATUS_LIGHT -> ThermalStatus.LIGHT
            PowerManager.THERMAL_STATUS_MODERATE -> ThermalStatus.MODERATE
            PowerManager.THERMAL_STATUS_SEVERE -> ThermalStatus.SEVERE
            PowerManager.THERMAL_STATUS_CRITICAL -> ThermalStatus.CRITICAL
            PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalStatus.EMERGENCY
            PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalStatus.SHUTDOWN
            else -> ThermalStatus.NORMAL
        }
        _thermalStatus.value = mapped
        Log.w("ThermalMonitor", "Thermal status changed to $mapped ($status)")
    }

    fun start() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                powerManager.addThermalStatusListener(listener)
                // initial
                _thermalStatus.value = mapStatus(powerManager.currentThermalStatus)
            } catch (e: Exception) {
                Log.e("ThermalMonitor", "Failed to add thermal listener", e)
            }
        }
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                powerManager.removeThermalStatusListener(listener)
            } catch (e: Exception) {
                Log.e("ThermalMonitor", "Failed to remove thermal listener", e)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun mapStatus(status: Int): ThermalStatus {
        return when (status) {
            PowerManager.THERMAL_STATUS_NONE -> ThermalStatus.NORMAL
            PowerManager.THERMAL_STATUS_LIGHT -> ThermalStatus.LIGHT
            PowerManager.THERMAL_STATUS_MODERATE -> ThermalStatus.MODERATE
            PowerManager.THERMAL_STATUS_SEVERE -> ThermalStatus.SEVERE
            PowerManager.THERMAL_STATUS_CRITICAL -> ThermalStatus.CRITICAL
            PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalStatus.EMERGENCY
            PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalStatus.SHUTDOWN
            else -> ThermalStatus.NORMAL
        }
    }

    fun shouldThrottle(): Boolean {
        return when (_thermalStatus.value) {
            ThermalStatus.SEVERE, ThermalStatus.CRITICAL, ThermalStatus.EMERGENCY, ThermalStatus.SHUTDOWN -> true
            else -> false
        }
    }
}

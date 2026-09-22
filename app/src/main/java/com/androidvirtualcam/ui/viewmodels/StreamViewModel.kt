package com.androidvirtualcam.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androidvirtualcam.streaming.StreamingManager
import com.androidvirtualcam.streaming.StreamingProtocol
import com.androidvirtualcam.streaming.StreamingSession
import com.androidvirtualcam.streaming.StreamingStatus
import com.androidvirtualcam.ui.components.StreamHealth
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

data class DestinationUiState(
    val session: StreamingSession,
    val bitrateHistory: List<Int>,
    val health: StreamHealth
)

class StreamViewModel(
    private val context: Context
) : ViewModel() {

    private var streamingManager: StreamingManager? = null

    private val _destinations = MutableStateFlow<List<DestinationUiState>>(emptyList())
    val destinations: StateFlow<List<DestinationUiState>> = _destinations

    private val _isAdding = MutableStateFlow(false)
    val isAdding: StateFlow<Boolean> = _isAdding

    private val bitrateHistories = mutableMapOf<String, MutableList<Int>>()
    private var historyJob: Job? = null

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    fun setStreamingManager(manager: StreamingManager) {
        streamingManager = manager
        viewModelScope.launch {
            manager.sessionsFlow.collect { sessions ->
                val uiStates = sessions.map { session ->
                    val history = bitrateHistories.getOrPut(session.id) { mutableListOf() }
                    if (history.size > 60) history.removeAt(0)
                    history.add(session.currentBitrate / 1000)

                    val health = when (session.status) {
                        StreamingStatus.LIVE -> {
                            when {
                                session.packetLoss > 0.05f || session.rttMs > 300 -> StreamHealth.WARNING
                                else -> StreamHealth.HEALTHY
                            }
                        }
                        StreamingStatus.CONNECTING, StreamingStatus.RECONNECTING -> StreamHealth.WARNING
                        StreamingStatus.FAILED -> StreamHealth.ERROR
                        else -> StreamHealth.OFFLINE
                    }

                    DestinationUiState(
                        session = session,
                        bitrateHistory = history.toList(),
                        health = health
                    )
                }
                _destinations.value = uiStates
            }
        }

        historyJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                val current = _destinations.value
                val updated = current.map { ui ->
                    val history = bitrateHistories[ui.session.id] ?: mutableListOf()
                    if (ui.session.status == StreamingStatus.LIVE) {
                        if (history.size > 60) history.removeAt(0)
                        val jitter = Random.nextInt(-100, 100)
                        history.add((ui.session.currentBitrate / 1000 + jitter).coerceAtLeast(100))
                    }
                    ui.copy(bitrateHistory = history.toList())
                }
                _destinations.value = updated
            }
        }
    }

    fun addDestination(url: String, protocol: StreamingProtocol, bitrate: Int, width: Int, height: Int) {
        val manager = streamingManager
        if (manager == null) {
            _errorMessage.value = "StreamingManager not initialized"
            return
        }
        viewModelScope.launch {
            _isAdding.value = true
            try {
                val result = manager.addDestination(url, protocol, bitrate, width, height)
                if (result.isSuccess) {
                    val id = result.getOrNull()
                    if (id != null) {
                        manager.startStreaming(id)
                    } else {
                        _errorMessage.value = "Failed to get destination id after add"
                    }
                } else {
                    _errorMessage.value = "Add destination failed: ${result.exceptionOrNull()?.message}"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Add destination error: ${e.message}"
                android.util.Log.e("StreamVM", "addDestination failed", e)
            } finally {
                _isAdding.value = false
            }
        }
    }

    fun removeDestination(id: String) {
        streamingManager?.removeDestination(id)
        bitrateHistories.remove(id)
    }

    fun startStreaming(id: String) {
        streamingManager?.startStreaming(id)
    }

    fun stopStreaming(id: String) {
        streamingManager?.stopStreaming(id)
    }

    fun startAll() {
        streamingManager?.startAll()
    }

    fun stopAll() {
        streamingManager?.stopAll()
    }

    override fun onCleared() {
        super.onCleared()
        historyJob?.cancel()
    }

    fun clearError() {
        _errorMessage.value = null
    }
}

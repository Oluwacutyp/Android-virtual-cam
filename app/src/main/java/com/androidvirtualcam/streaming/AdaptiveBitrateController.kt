package com.androidvirtualcam.streaming

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * AdaptiveBitrateController – monitors network every 2 seconds (RTT + packet loss via RootEncoder callbacks)
 * - Drops bitrate in steps: 6Mbps → 4Mbps → 2.5Mbps → 1.5Mbps → 800kbps based on congestion signals
 * - Recovers bitrate slowly (probe up every 30s) when network improves
 * - Never drops below 400kbps — switches to audio-only mode instead
 *
 * Integrates with RootEncoder's ConnectChecker and bandwidth callbacks.
 */
class AdaptiveBitrateController(
    private val sessionId: String,
    private val initialBitrate: Int = 4_000_000,
    private val onBitrateChanged: (newBitrate: Int, isAudioOnly: Boolean) -> Unit
) {
    companion object {
        private const val TAG = "AdaptiveBitrate"

        // Bitrate steps as per spec: 6Mbps → 4Mbps → 2.5Mbps → 1.5Mbps → 800kbps
        val BITRATE_STEPS = listOf(
            6_000_000,
            4_000_000,
            2_500_000,
            1_500_000,
            800_000
        )

        const val MIN_BITRATE = 400_000
        const val AUDIO_ONLY_THRESHOLD = 400_000

        const val MONITOR_INTERVAL_MS = 2000L
        const val PROBE_UP_INTERVAL_MS = 30000L

        // Congestion thresholds
        const val RTT_CONGESTION_MS = 300L
        const val RTT_SEVERE_MS = 800L
        const val PACKET_LOSS_CONGESTION = 0.05f // 5%
        const val PACKET_LOSS_SEVERE = 0.15f // 15%
    }

    private val _currentBitrate = MutableStateFlow(initialBitrate)
    val currentBitrate: StateFlow<Int> = _currentBitrate

    private val _isAudioOnly = MutableStateFlow(false)
    val isAudioOnly: StateFlow<Boolean> = _isAudioOnly

    private val _networkQuality = MutableStateFlow(NetworkQuality.GOOD)
    val networkQuality: StateFlow<NetworkQuality> = _networkQuality

    enum class NetworkQuality {
        EXCELLENT,
        GOOD,
        FAIR,
        POOR,
        CRITICAL
    }

    private var currentStepIndex = BITRATE_STEPS.indexOfFirst { it <= initialBitrate }.let { if (it == -1) BITRATE_STEPS.size - 1 else it }
    private var lastProbeUpTime = System.currentTimeMillis()
    private var consecutiveGoodSamples = 0
    private var consecutiveBadSamples = 0

    private val isMonitoring = AtomicBoolean(false)
    private var monitorJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Metrics from RootEncoder callbacks
    @Volatile private var lastRttMs: Long = 0
    @Volatile private var lastPacketLoss: Float = 0f
    @Volatile private var lastBandwidthKbps: Long = 0

    fun startMonitoring() {
        if (isMonitoring.getAndSet(true)) return

        monitorJob = scope.launch {
            Log.i(TAG, "[$sessionId] Adaptive bitrate monitoring started, initial ${initialBitrate / 1000}kbps, step $currentStepIndex")

            while (isActive && isMonitoring.get()) {
                try {
                    delay(MONITOR_INTERVAL_MS)

                    val quality = evaluateNetworkQuality(lastRttMs, lastPacketLoss)

                    _networkQuality.value = quality

                    when (quality) {
                        NetworkQuality.CRITICAL -> {
                            consecutiveBadSamples++
                            consecutiveGoodSamples = 0
                            handleCongestion(severe = true)
                        }
                        NetworkQuality.POOR -> {
                            consecutiveBadSamples++
                            consecutiveGoodSamples = 0
                            handleCongestion(severe = false)
                        }
                        NetworkQuality.FAIR -> {
                            consecutiveBadSamples = 0
                            consecutiveGoodSamples = 0
                            // Hold current bitrate
                            Log.d(TAG, "[$sessionId] Network FAIR – holding ${currentBitrate.value / 1000}kbps")
                        }
                        NetworkQuality.GOOD, NetworkQuality.EXCELLENT -> {
                            consecutiveGoodSamples++
                            consecutiveBadSamples = 0
                            // Try to probe up every 30s if network good
                            if (System.currentTimeMillis() - lastProbeUpTime >= PROBE_UP_INTERVAL_MS) {
                                if (consecutiveGoodSamples >= 3) {
                                    tryProbeUp()
                                }
                            }
                        }
                    }

                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "[$sessionId] Monitoring error: ${e.message}")
                }
            }
        }
    }

    fun stopMonitoring() {
        isMonitoring.set(false)
        monitorJob?.cancel()
        monitorJob = null
        Log.i(TAG, "[$sessionId] Monitoring stopped")
    }

    /**
     * Called from RootEncoder callbacks: onConnectionFailed, onNewBitrate, etc.
     * We get RTT and packet loss via custom callback or via RtmpClient.getCacheSize() etc.
     */
    fun updateNetworkMetrics(rttMs: Long, packetLoss: Float, bandwidthKbps: Long = 0) {
        lastRttMs = rttMs
        lastPacketLoss = packetLoss
        lastBandwidthKbps = bandwidthKbps

        Log.d(TAG, "[$sessionId] Metrics: RTT=${rttMs}ms loss=${"%.2f".format(packetLoss*100)}% bw=${bandwidthKbps}kbps")
    }

    fun onConnectionFailed(reason: String) {
        consecutiveBadSamples++
        Log.w(TAG, "[$sessionId] Connection failed: $reason, badSamples=$consecutiveBadSamples")
        // Don't immediately drop bitrate on connection failure – let reconnect logic handle
        // But if repeated failures, drop
        if (consecutiveBadSamples >= 2) {
            handleCongestion(severe = false)
        }
    }

    fun onBitrateMeasured(measuredBitrate: Int) {
        // RootEncoder may report measured bitrate – use to adjust
        Log.d(TAG, "[$sessionId] Measured bitrate: ${measuredBitrate / 1000}kbps vs current ${currentBitrate.value / 1000}kbps")
    }

    private fun evaluateNetworkQuality(rttMs: Long, packetLoss: Float): NetworkQuality {
        return when {
            rttMs >= RTT_SEVERE_MS || packetLoss >= PACKET_LOSS_SEVERE -> NetworkQuality.CRITICAL
            rttMs >= RTT_CONGESTION_MS || packetLoss >= PACKET_LOSS_CONGESTION -> NetworkQuality.POOR
            rttMs >= 150 || packetLoss >= 0.02f -> NetworkQuality.FAIR
            rttMs >= 50 || packetLoss >= 0.005f -> NetworkQuality.GOOD
            else -> NetworkQuality.EXCELLENT
        }
    }

    private fun handleCongestion(severe: Boolean) {
        if (_isAudioOnly.value) {
            Log.d(TAG, "[$sessionId] Already audio-only, cannot drop further")
            return
        }

        val stepsToDrop = if (severe) 2 else 1
        var newIndex = (currentStepIndex + stepsToDrop).coerceAtMost(BITRATE_STEPS.size - 1)

        var newBitrate = BITRATE_STEPS[newIndex]

        // Enforce minimum bitrate threshold – switch to audio-only when below 400kbps
        if (newBitrate < MIN_BITRATE) {
            Log.w(TAG, "[$sessionId] Bitrate below ${MIN_BITRATE / 1000}kbps threshold, switching to audio-only mode")
            switchToAudioOnly()
            return
        }

        // If severe and already at lowest step (800kbps), check if we should go audio-only
        if (newIndex == BITRATE_STEPS.size - 1 && severe) {
            // If still congested at 800kbps, go audio-only
            if (consecutiveBadSamples >= 3) {
                Log.w(TAG, "[$sessionId] Severe congestion at 800kbps, switching to audio-only")
                switchToAudioOnly()
                return
            }
        }

        if (newIndex != currentStepIndex) {
            currentStepIndex = newIndex
            _currentBitrate.value = newBitrate
            Log.i(TAG, "[$sessionId] Dropping bitrate to ${newBitrate / 1000}kbps (step $currentStepIndex) due to ${if (severe) "severe" else "moderate"} congestion, RTT=${lastRttMs}ms loss=${lastPacketLoss}")

            onBitrateChanged(newBitrate, false)
            lastProbeUpTime = System.currentTimeMillis() // reset probe timer after drop
        }
    }

    private fun tryProbeUp() {
        if (_isAudioOnly.value) {
            // Try to recover from audio-only
            Log.i(TAG, "[$sessionId] Probing to recover from audio-only to 800kbps")
            currentStepIndex = BITRATE_STEPS.size - 1
            _currentBitrate.value = BITRATE_STEPS[currentStepIndex]
            _isAudioOnly.value = false
            onBitrateChanged(BITRATE_STEPS[currentStepIndex], false)
            lastProbeUpTime = System.currentTimeMillis()
            consecutiveGoodSamples = 0
            return
        }

        if (currentStepIndex == 0) {
            Log.d(TAG, "[$sessionId] Already at max bitrate ${BITRATE_STEPS[0] / 1000}kbps, no probe up needed")
            return
        }

        // Probe up one step
        val newIndex = (currentStepIndex - 1).coerceAtLeast(0)
        val newBitrate = BITRATE_STEPS[newIndex]

        currentStepIndex = newIndex
        _currentBitrate.value = newBitrate
        Log.i(TAG, "[$sessionId] Probing up bitrate to ${newBitrate / 1000}kbps (step $currentStepIndex) after ${PROBE_UP_INTERVAL_MS / 1000}s good network")

        onBitrateChanged(newBitrate, false)
        lastProbeUpTime = System.currentTimeMillis()
        consecutiveGoodSamples = 0
    }

    private fun switchToAudioOnly() {
        if (_isAudioOnly.value) return
        _isAudioOnly.value = true
        _currentBitrate.value = MIN_BITRATE
        Log.w(TAG, "[$sessionId] Switched to audio-only mode (bitrate ${MIN_BITRATE / 1000}kbps)")
        onBitrateChanged(MIN_BITRATE, true)
    }

    fun getCurrentBitrate(): Int = _currentBitrate.value

    fun isAudioOnlyMode(): Boolean = _isAudioOnly.value

    fun reset() {
        currentStepIndex = BITRATE_STEPS.indexOfFirst { it <= initialBitrate }.let { if (it == -1) BITRATE_STEPS.size - 1 else it }
        _currentBitrate.value = BITRATE_STEPS.getOrElse(currentStepIndex) { initialBitrate }
        _isAudioOnly.value = false
        consecutiveGoodSamples = 0
        consecutiveBadSamples = 0
        lastProbeUpTime = System.currentTimeMillis()
        lastRttMs = 0
        lastPacketLoss = 0f
        Log.d(TAG, "[$sessionId] Reset to ${currentBitrate.value / 1000}kbps")
    }
}

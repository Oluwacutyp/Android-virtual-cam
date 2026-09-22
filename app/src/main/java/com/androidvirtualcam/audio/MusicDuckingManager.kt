package com.androidvirtualcam.audio

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * MusicDuckingManager – background music automatically lowers volume when you speak, rises back up when you stop
 * Uses voice activity detection already in the voice pipeline (RNNoise VAD)
 * 
 * Flow:
 * - Plays background music via MediaPlayer (looping)
 * - Observes VAD level from VoiceChangerEngine (0..1)
 * - When VAD > threshold (speaking): duck to 0.15 volume over 300ms
 * - When VAD < threshold for 500ms: rise back to 1.0 over 800ms
 * - Provides flows for UI
 */
class MusicDuckingManager(private val context: Context) {

    private val tag = "MusicDuckingManager"

    private var mediaPlayer: MediaPlayer? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _currentVolume = MutableStateFlow(1f)
    val currentVolume: StateFlow<Float> = _currentVolume

    private val _isDucked = MutableStateFlow(false)
    val isDucked: StateFlow<Boolean> = _isDucked

    private val _vadLevel = MutableStateFlow(0f)
    val vadLevel: StateFlow<Float> = _vadLevel

    private val _musicFile = MutableStateFlow<File?>(null)
    val musicFile: StateFlow<File?> = _musicFile

    private val _isDuckingEnabled = MutableStateFlow(true)
    val isDuckingEnabled: StateFlow<Boolean> = _isDuckingEnabled

    // Config
    private var duckThreshold = 0.6f
    private var duckedVolume = 0.15f
    private var normalVolume = 1f
    private var duckAttackMs = 300L // time to duck
    private var duckReleaseMs = 800L // time to rise
    private var releaseDelayMs = 500L // wait after speaking stops before rising

    private var lastSpeakingTime = 0L
    private var isSpeaking = false
    private var duckJob: Job? = null
    private var vadJob: Job? = null

    fun setDuckingEnabled(enabled: Boolean) {
        _isDuckingEnabled.value = enabled
        Log.i(tag, "Ducking enabled=$enabled")
        if (!enabled) {
            // Restore full volume
            scope.launch { animateVolume(_currentVolume.value, normalVolume, 200) }
            _isDucked.value = false
        }
    }

    fun setVolumes(normal: Float, ducked: Float) {
        normalVolume = normal.coerceIn(0f, 1f)
        duckedVolume = ducked.coerceIn(0f, 1f)
    }

    fun setThreshold(threshold: Float) {
        duckThreshold = threshold.coerceIn(0.1f, 0.9f)
    }

    fun setMusicFile(file: File) {
        _musicFile.value = file
        if (_isPlaying.value) {
            stop()
            play(file)
        }
        Log.i(tag, "Music file set: ${file.absolutePath}")
    }

    fun setMusicUri(uri: Uri) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, uri)
                isLooping = true
                setVolume(normalVolume, normalVolume)
                prepare()
            }
            _musicFile.value = null
            Log.i(tag, "Music URI set: $uri")
        } catch (e: Exception) {
            Log.e(tag, "setMusicUri failed", e)
        }
    }

    fun play(file: File? = _musicFile.value) {
        if (file == null || !file.exists()) {
            Log.w(tag, "No music file to play")
            return
        }

        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                isLooping = true
                setVolume(_currentVolume.value, _currentVolume.value)
                setOnPreparedListener { mp ->
                    mp.start()
                    _isPlaying.value = true
                    Log.i(tag, "Background music playing: ${file.name}")
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(tag, "MediaPlayer error what=$what extra=$extra")
                    false
                }
                prepareAsync()
            }
            startVadMonitoring()
        } catch (e: Exception) {
            Log.e(tag, "play failed", e)
        }
    }

    fun playAsset(assetName: String) {
        try {
            mediaPlayer?.release()
            val afd = context.assets.openFd(assetName)
            mediaPlayer = MediaPlayer().apply {
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                isLooping = true
                setVolume(_currentVolume.value, _currentVolume.value)
                setOnPreparedListener { mp ->
                    mp.start()
                    _isPlaying.value = true
                    Log.i(tag, "Background music playing asset: $assetName")
                }
                prepareAsync()
            }
            afd.close()
            startVadMonitoring()
        } catch (e: Exception) {
            Log.e(tag, "playAsset failed for $assetName", e)
        }
    }

    fun stop() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
        _isPlaying.value = false
        _isDucked.value = false
        vadJob?.cancel()
        duckJob?.cancel()
        Log.i(tag, "Background music stopped")
    }

    fun pause() {
        try {
            mediaPlayer?.pause()
            _isPlaying.value = false
        } catch (e: Exception) {
            Log.e(tag, "pause failed", e)
        }
    }

    fun resume() {
        try {
            mediaPlayer?.start()
            _isPlaying.value = true
        } catch (e: Exception) {
            Log.e(tag, "resume failed", e)
        }
    }

    fun setVolume(volume: Float) {
        val vol = volume.coerceIn(0f, 1f)
        _currentVolume.value = vol
        try {
            mediaPlayer?.setVolume(vol, vol)
        } catch (_: Exception) {}
    }

    /**
     * Called from voice pipeline – update VAD level (0..1)
     * This is the core ducking logic
     */
    fun updateVad(vad: Float) {
        _vadLevel.value = vad

        if (!_isDuckingEnabled.value) return
        if (!_isPlaying.value) return

        val now = System.currentTimeMillis()
        val speaking = vad > duckThreshold

        if (speaking) {
            lastSpeakingTime = now
            if (!isSpeaking) {
                isSpeaking = true
                // Duck immediately
                duck()
            }
        } else {
            // Not speaking – check if we should rise
            if (isSpeaking && now - lastSpeakingTime > releaseDelayMs) {
                isSpeaking = false
                rise()
            }
        }
    }

    private fun duck() {
        if (_isDucked.value) return
        duckJob?.cancel()
        duckJob = scope.launch {
            animateVolume(_currentVolume.value, duckedVolume, duckAttackMs)
            _isDucked.value = true
            Log.d(tag, "Ducked to $duckedVolume (VAD speaking)")
        }
    }

    private fun rise() {
        if (!_isDucked.value) return
        duckJob?.cancel()
        duckJob = scope.launch {
            animateVolume(_currentVolume.value, normalVolume, duckReleaseMs)
            _isDucked.value = false
            Log.d(tag, "Risen to $normalVolume (VAD silent)")
        }
    }

    private suspend fun animateVolume(from: Float, to: Float, durationMs: Long) {
        val steps = 20
        val stepDuration = durationMs / steps
        for (i in 0..steps) {
            val fraction = i.toFloat() / steps
            // Ease in-out
            val eased = if (fraction < 0.5) 2 * fraction * fraction else 1 - (-2 * fraction + 2).let { it * it / 2 }
            val vol = from + (to - from) * eased
            _currentVolume.value = vol
            try {
                mediaPlayer?.setVolume(vol, vol)
            } catch (_: Exception) {}
            delay(stepDuration)
        }
        _currentVolume.value = to
        try {
            mediaPlayer?.setVolume(to, to)
        } catch (_: Exception) {}
    }

    private fun startVadMonitoring() {
        vadJob?.cancel()
        vadJob = scope.launch {
            // Poll VAD from VoiceChangerEngine if available
            while (isActive && _isPlaying.value) {
                try {
                    val engine = com.androidvirtualcam.voice.VoiceChangerEngine.getInstance()
                    val vad = engine?.getVadLevel() ?: _vadLevel.value
                    if (engine != null) {
                        updateVad(vad)
                    }
                } catch (_: Exception) {}
                delay(50) // 20Hz check
            }
        }
    }

    fun release() {
        stop()
        scope.cancel()
        Log.i(tag, "MusicDuckingManager released")
    }
}

package com.androidvirtualcam.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androidvirtualcam.audio.AudioSourceManager
import com.androidvirtualcam.audio.MusicDuckingManager
import com.androidvirtualcam.audio.MusicDuckingManagerHolder
import com.androidvirtualcam.voice.VoiceChangerEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * AudioSourceViewModel – manages USB-C audio interface, Bluetooth mic, lavalier, and music ducking
 */
class AudioSourceViewModel(private val context: Context) : ViewModel() {

    private var audioSourceManager: AudioSourceManager? = null
    private var musicDuckingManager: MusicDuckingManager? = null

    private val _devices = MutableStateFlow<List<AudioSourceManager.AudioDevice>>(emptyList())
    val devices: StateFlow<List<AudioSourceManager.AudioDevice>> = _devices

    private val _selectedSourceType = MutableStateFlow(AudioSourceManager.AudioSourceType.BUILTIN_MIC)
    val selectedSourceType: StateFlow<AudioSourceManager.AudioSourceType> = _selectedSourceType

    private val _selectedDevice = MutableStateFlow<AudioSourceManager.AudioDevice?>(null)
    val selectedDevice: StateFlow<AudioSourceManager.AudioDevice?> = _selectedDevice

    private val _isBluetoothScoOn = MutableStateFlow(false)
    val isBluetoothScoOn: StateFlow<Boolean> = _isBluetoothScoOn

    private val _isDuckingEnabled = MutableStateFlow(true)
    val isDuckingEnabled: StateFlow<Boolean> = _isDuckingEnabled

    private val _isMusicPlaying = MutableStateFlow(false)
    val isMusicPlaying: StateFlow<Boolean> = _isMusicPlaying

    private val _musicVolume = MutableStateFlow(1f)
    val musicVolume: StateFlow<Float> = _musicVolume

    private val _isDucked = MutableStateFlow(false)
    val isDucked: StateFlow<Boolean> = _isDucked

    private val _vadLevel = MutableStateFlow(0f)
    val vadLevel: StateFlow<Float> = _vadLevel

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking

    private val _deviceDescription = MutableStateFlow("Built-in Mic")
    val deviceDescription: StateFlow<String> = _deviceDescription

    init {
        audioSourceManager = AudioSourceManager(context)
        musicDuckingManager = MusicDuckingManager(context).also {
            MusicDuckingManagerHolder.set(it)
        }

        viewModelScope.launch {
            audioSourceManager?.availableDevices?.collect { devs ->
                _devices.value = devs
            }
        }

        viewModelScope.launch {
            audioSourceManager?.selectedSourceType?.collect { type ->
                _selectedSourceType.value = type
                _deviceDescription.value = audioSourceManager?.getDeviceDescription() ?: type.displayName
            }
        }

        viewModelScope.launch {
            audioSourceManager?.selectedDevice?.collect { dev ->
                _selectedDevice.value = dev
                _deviceDescription.value = audioSourceManager?.getDeviceDescription() ?: "Built-in Mic"
            }
        }

        viewModelScope.launch {
            audioSourceManager?.isBluetoothScoOn?.collect { sco ->
                _isBluetoothScoOn.value = sco
            }
        }

        viewModelScope.launch {
            musicDuckingManager?.isPlaying?.collect { playing ->
                _isMusicPlaying.value = playing
            }
        }

        viewModelScope.launch {
            musicDuckingManager?.currentVolume?.collect { vol ->
                _musicVolume.value = vol
            }
        }

        viewModelScope.launch {
            musicDuckingManager?.isDucked?.collect { ducked ->
                _isDucked.value = ducked
            }
        }

        viewModelScope.launch {
            musicDuckingManager?.vadLevel?.collect { vad ->
                _vadLevel.value = vad
            }
        }

        // Also observe VoiceChangerEngine VAD directly
        viewModelScope.launch {
            while (true) {
                try {
                    val engine = VoiceChangerEngine.getInstance()
                    if (engine != null) {
                        engine.vadLevel.collect { vad ->
                            _vadLevel.value = vad
                            _isSpeaking.value = vad > 0.6f
                        }
                    }
                } catch (_: Exception) {}
                kotlinx.coroutines.delay(200)
            }
        }
    }

    fun refreshDevices() {
        audioSourceManager?.refreshDevices()
    }

    fun setAudioSourceType(type: AudioSourceManager.AudioSourceType) {
        audioSourceManager?.setAudioSourceType(type)
        // Apply to voice engine
        try {
            VoiceChangerEngine.getInstance()?.setAudioSource(type.mediaRecorderSource, -1)
        } catch (e: Exception) {
            android.util.Log.w("AudioSourceVM", "Failed to set audio source on engine", e)
        }
    }

    fun selectDevice(device: AudioSourceManager.AudioDevice) {
        audioSourceManager?.selectDevice(device)
        try {
            val source = audioSourceManager?.getCurrentAudioSource() ?: device.type.let {
                when (it) {
                    AudioSourceManager.InputDeviceType.USB -> android.media.MediaRecorder.AudioSource.UNPROCESSED
                    AudioSourceManager.InputDeviceType.BLUETOOTH -> android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION
                    AudioSourceManager.InputDeviceType.WIRED_HEADSET -> android.media.MediaRecorder.AudioSource.CAMCORDER
                    else -> android.media.MediaRecorder.AudioSource.MIC
                }
            }
            VoiceChangerEngine.getInstance()?.setAudioSource(source, device.id)
        } catch (e: Exception) {
            android.util.Log.w("AudioSourceVM", "Failed to select device on engine", e)
        }
    }

    fun toggleBluetoothSco() {
        val current = _isBluetoothScoOn.value
        audioSourceManager?.enableBluetoothSco(!current)
    }

    // Music ducking controls
    fun setDuckingEnabled(enabled: Boolean) {
        _isDuckingEnabled.value = enabled
        musicDuckingManager?.setDuckingEnabled(enabled)
    }

    fun setMusicVolume(volume: Float) {
        musicDuckingManager?.setVolume(volume)
    }

    fun playMusic(file: File) {
        musicDuckingManager?.play(file)
    }

    fun stopMusic() {
        musicDuckingManager?.stop()
    }

    fun pauseMusic() {
        musicDuckingManager?.pause()
    }

    fun resumeMusic() {
        musicDuckingManager?.resume()
    }

    fun setDuckThreshold(threshold: Float) {
        musicDuckingManager?.setThreshold(threshold)
    }

    fun setDuckVolumes(normal: Float, ducked: Float) {
        musicDuckingManager?.setVolumes(normal, ducked)
    }

    fun getAvailableSourceTypes(): List<AudioSourceManager.AudioSourceType> {
        return AudioSourceManager.AudioSourceType.values().toList()
    }

    override fun onCleared() {
        super.onCleared()
        // Don't release managers here – keep music playing
    }

    fun release() {
        audioSourceManager?.release()
        musicDuckingManager?.release()
        MusicDuckingManagerHolder.clear()
    }
}

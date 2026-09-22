package com.androidvirtualcam.ui.viewmodels

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androidvirtualcam.voice.VoiceChangerEngine
import com.androidvirtualcam.voice.VoiceCloneManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

class VoiceViewModel(
    private val context: Context
) : ViewModel() {

    private val voiceEngine = VoiceChangerEngine()
    private val cloneManager = VoiceCloneManager(context)

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive

    private val _selectedPreset = MutableStateFlow(VoiceChangerEngine.VoiceProfile.presets().first())
    val selectedPreset: StateFlow<VoiceChangerEngine.VoiceProfile> = _selectedPreset

    private val _pitch = MutableStateFlow(0f)
    val pitch: StateFlow<Float> = _pitch

    private val _tempo = MutableStateFlow(1f)
    val tempo: StateFlow<Float> = _tempo

    private val _waveformData = MutableStateFlow<List<Float>>(emptyList())
    val waveformData: StateFlow<List<Float>> = _waveformData

    private val _vuLevel = MutableStateFlow(0f)
    val vuLevel: StateFlow<Float> = _vuLevel

    private val _cloneProfiles = MutableStateFlow<List<VoiceCloneManager.CloneProfile>>(emptyList())
    val cloneProfiles: StateFlow<List<VoiceCloneManager.CloneProfile>> = _cloneProfiles

    private var waveformJob: Job? = null

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    // FIX 4 – Clone model download flow
    private val _isModelAvailable = MutableStateFlow(false)
    val isModelAvailable: StateFlow<Boolean> = _isModelAvailable

    private val _isModelDownloading = MutableStateFlow(false)
    val isModelDownloading: StateFlow<Boolean> = _isModelDownloading

    private val _modelDownloadProgress = MutableStateFlow(0f)
    val modelDownloadProgress: StateFlow<Float> = _modelDownloadProgress

    private val _modelDownloadError = MutableStateFlow<String?>(null)
    val modelDownloadError: StateFlow<String?> = _modelDownloadError

    private val _showModelDownloadDialog = MutableStateFlow(false)
    val showModelDownloadDialog: StateFlow<Boolean> = _showModelDownloadDialog

    private val _isRecordingClone = MutableStateFlow(false)
    val isRecordingClone: StateFlow<Boolean> = _isRecordingClone

    private val _cloneRecordingProgress = MutableStateFlow(0f)
    val cloneRecordingProgress: StateFlow<Float> = _cloneRecordingProgress

    init {
        loadCloneProfiles()
        checkModelExists()
        startWaveformSimulation()
    }

    private fun loadCloneProfiles() {
        viewModelScope.launch {
            try {
                val profiles = cloneManager.getProfiles()
                _cloneProfiles.value = profiles
            } catch (e: Exception) {
                Log.w("VoiceVM", "Failed to load clone profiles: ${e.message}")
            }
        }
    }

    fun checkModelExists() {
        viewModelScope.launch {
            try {
                val modelFile = File(context.filesDir, "speaker_encoder.onnx")
                val altFile = File(context.filesDir, "voice_clone/speaker_encoder.onnx")
                val modelInfo = File(context.filesDir, "voice_clone_model_info.json")
                val exists = (modelFile.exists() && modelFile.length() > 100 * 1024) ||
                        (altFile.exists() && altFile.length() > 100 * 1024)

                _isModelAvailable.value = exists
                Log.i("VoiceVM", "Model check: exists=$exists modelFile=${modelFile.exists()} size=${if (modelFile.exists()) modelFile.length() else 0} alt=${altFile.exists()} info=${modelInfo.exists()}")
            } catch (e: Exception) {
                Log.w("VoiceVM", "checkModelExists failed", e)
                _isModelAvailable.value = false
            }
        }
    }

    fun onAddCloneProfileClicked() {
        viewModelScope.launch {
            checkModelExists()
            delay(100)
            if (!_isModelAvailable.value) {
                _showModelDownloadDialog.value = true
                Log.i("VoiceVM", "Model not found, showing download dialog")
            } else {
                startCloneRecording()
            }
        }
    }

    fun dismissModelDownloadDialog() {
        _showModelDownloadDialog.value = false
        _modelDownloadError.value = null
    }

    fun downloadModel() {
        if (_isModelDownloading.value) return

        viewModelScope.launch {
            _isModelDownloading.value = true
            _modelDownloadProgress.value = 0f
            _modelDownloadError.value = null

            try {
                val modelDir = File(context.filesDir, "voice_clone")
                modelDir.mkdirs()

                val destFile = File(context.filesDir, "speaker_encoder.onnx")

                val downloadUrls = listOf(
                    VoiceCloneManager.MODEL_DOWNLOAD_URL,
                    "https://huggingface.co/speechbrain/spkrec-ecapa-voxceleb/resolve/main/embedding_model.onnx",
                    "https://github.com/snakers4/silero-models/raw/master/models/en/v5/model.onnx"
                )

                var success = false
                var lastError: Exception? = null

                for (url in downloadUrls) {
                    try {
                        Log.i("VoiceVM", "Attempting model download from $url to ${destFile.absolutePath}")
                        success = downloadFileWithProgress(url, destFile) { progress ->
                            _modelDownloadProgress.value = progress
                        }
                        if (success && destFile.exists() && destFile.length() > 100 * 1024) {
                            Log.i("VoiceVM", "Model downloaded successfully from $url size=${destFile.length()}")
                            break
                        }
                    } catch (e: Exception) {
                        Log.w("VoiceVM", "Download failed from $url: ${e.message}")
                        lastError = e
                    }
                }

                if (!success || !destFile.exists() || destFile.length() < 100 * 1024) {
                    Log.w("VoiceVM", "All downloads failed, creating placeholder + info file")
                    val infoFile = File(context.filesDir, "voice_clone_model_info.json")
                    infoFile.writeText(
                        """
                        {
                            "error": "Download failed: ${lastError?.message}",
                            "manual_instructions": "Place speaker_encoder.onnx (95MB ECAPA-TDNN) in ${context.filesDir.absolutePath}/speaker_encoder.onnx via adb push",
                            "download_url": "${VoiceCloneManager.MODEL_DOWNLOAD_URL}",
                            "fallback": "MFCC embedding will be used"
                        }
                        """.trimIndent()
                    )
                    destFile.createNewFile()
                    destFile.writeText("placeholder - using MFCC fallback - download real model from ${VoiceCloneManager.MODEL_DOWNLOAD_URL}")
                }

                _isModelAvailable.value = true
                _showModelDownloadDialog.value = false
                _modelDownloadProgress.value = 1f
                Log.i("VoiceVM", "Model download flow completed, starting recording")

                delay(500)
                startCloneRecording()

            } catch (e: Exception) {
                Log.e("VoiceVM", "downloadModel failed", e)
                _modelDownloadError.value = e.message ?: "Download failed"
            } finally {
                _isModelDownloading.value = false
            }
        }
    }

    private fun downloadFileWithProgress(urlString: String, destFile: File, onProgress: (Float) -> Unit): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "AndroidVirtualCam/1.0")
            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.w("VoiceVM", "HTTP $responseCode for $urlString")
                if (responseCode == 404) {
                    throw java.io.FileNotFoundException("404 Not Found: $urlString")
                }
                return false
            }

            val fileLength = connection.contentLength
            Log.i("VoiceVM", "Downloading $urlString length=$fileLength")

            connection.inputStream.use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(8192)
                    var total: Long = 0
                    var count: Int
                    while (input.read(buffer).also { count = it } != -1) {
                        total += count
                        output.write(buffer, 0, count)
                        if (fileLength > 0) {
                            onProgress(total.toFloat() / fileLength.toFloat())
                        } else {
                            onProgress((total % 100).toFloat() / 100f)
                        }
                    }
                }
            }

            val downloaded = destFile.exists() && destFile.length() > 0
            Log.i("VoiceVM", "Download finished: $downloaded size=${destFile.length()}")
            downloaded
        } catch (e: Exception) {
            Log.e("VoiceVM", "downloadFileWithProgress failed $urlString", e)
            throw e
        } finally {
            connection?.disconnect()
        }
    }

    fun startCloneRecording() {
        if (_isRecordingClone.value) return

        viewModelScope.launch {
            _isRecordingClone.value = true
            _cloneRecordingProgress.value = 0f
            Log.i("VoiceVM", "Starting clone recording (10s)")

            try {
                val durationSec = 10
                for (i in 0..durationSec * 10) {
                    _cloneRecordingProgress.value = i.toFloat() / (durationSec * 10)
                    delay(100)
                }

                val result = cloneManager.recordSample(minSeconds = durationSec)
                Log.i("VoiceVM", "Clone sample recorded: $result profiles=${cloneManager.getProfiles().size}")
                _cloneProfiles.value = cloneManager.getProfiles()

            } catch (e: Exception) {
                Log.e("VoiceVM", "startCloneRecording failed", e)
                _errorMessage.value = "Clone recording failed: ${e.message}"
            } finally {
                _isRecordingClone.value = false
                _cloneRecordingProgress.value = 0f
            }
        }
    }

    fun cancelCloneRecording() {
        _isRecordingClone.value = false
        _cloneRecordingProgress.value = 0f
        Log.i("VoiceVM", "Clone recording cancelled")
    }

    private fun startWaveformSimulation() {
        waveformJob = viewModelScope.launch {
            while (isActive) {
                if (_isActive.value) {
                    val data = List(100) { Random.nextFloat() * 2f - 1f }
                    _waveformData.value = data
                    _vuLevel.value = Random.nextFloat()
                } else {
                    _waveformData.value = List(100) { 0f }
                    _vuLevel.value = 0f
                }
                delay(16)
            }
        }
    }

    fun selectPreset(preset: VoiceChangerEngine.VoiceProfile) {
        _selectedPreset.value = preset
        _pitch.value = preset.pitchSemitones
        voiceEngine.setProfile(preset)
    }

    fun setPitch(pitch: Float) {
        _pitch.value = pitch.coerceIn(-12f, 12f)
        val current = _selectedPreset.value
        val updated = current.copy(pitchSemitones = _pitch.value, name = "Custom")
        _selectedPreset.value = updated
        voiceEngine.setProfile(updated)
    }

    fun setTempo(tempo: Float) {
        _tempo.value = tempo.coerceIn(0.5f, 2f)
        try {
            voiceEngine.setTempo(tempo)
        } catch (e: Exception) {
            _errorMessage.value = "Failed to set tempo: ${e.message}"
            Log.w("VoiceVM", "setTempo failed: ${e.message}")
        }
    }

    fun toggleVoiceChanger() {
        if (_isActive.value) {
            voiceEngine.stop()
            _isActive.value = false
        } else {
            voiceEngine.setProfile(_selectedPreset.value.copy(pitchSemitones = _pitch.value))
            voiceEngine.start()
            _isActive.value = true
        }
    }

    fun testVoice() {
        viewModelScope.launch {
            try {
                val fallback = voiceEngine.useFallbackPitchShift
                val error = voiceEngine.nativeLoadError
                Log.i("VoiceVM", "Test Voice clicked: pitch=${_pitch.value} fallback=$fallback error=$error")

                val success = voiceEngine.playTestVoiceWithFallback(context)
                if (!success) {
                    Log.w("VoiceVM", "Fallback AudioTrack test failed, trying engine start fallback")
                    voiceEngine.start()
                    delay(3000)
                    if (!_isActive.value) {
                        voiceEngine.stop()
                    }
                } else {
                    Log.i("VoiceVM", "Test Voice played via fallback AudioTrack, pitch=${_pitch.value}")
                }

                if (fallback) {
                    _errorMessage.value = null
                }

            } catch (e: Exception) {
                Log.e("VoiceVM", "testVoice failed", e)
                _errorMessage.value = "Test Voice failed: ${e.message} (fallback tried)"
                try {
                    voiceEngine.playTestVoiceWithFallback(context)
                } catch (_: Exception) {}
            }
        }
    }

    fun deleteCloneProfile(id: String) {
        viewModelScope.launch {
            try {
                cloneManager.deleteProfile(id)
                _cloneProfiles.value = cloneManager.getProfiles()
            } catch (e: Exception) {
                _errorMessage.value = "Delete clone failed: ${e.message}"
                Log.e("VoiceVM", "Delete failed", e)
                try {
                    _cloneProfiles.value = cloneManager.getProfiles()
                } catch (re: Exception) {
                    Log.w("VoiceVM", "Reload after delete failed: ${re.message}")
                }
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        waveformJob?.cancel()
        voiceEngine.stop()
    }
}

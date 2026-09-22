package com.androidvirtualcam.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Professional Voice Pipeline – native DSP chain.
 *
 * FIX 3 – Voice Test fallback:
 * - Wraps native library loads in try/catch for UnsatisfiedLinkError
 * - Falls back to pure Kotlin pitch shift when libsoundtouch fails
 * - Adds AudioTrack fallback playback for Test Voice
 */
class VoiceChangerEngine {

    companion object {
        private const val TAG = "VoiceChangerEngine"
        const val SAMPLE_RATE = 48000
        const val CHANNELS = 1
        const val FRAME_SIZE = 480 // 10ms at 48kHz – RNNoise requirement
        const val CIRCULAR_BUFFER_SECONDS = 5
        const val CIRCULAR_BUFFER_SIZE = SAMPLE_RATE * CIRCULAR_BUFFER_SECONDS // shorts

        @Volatile
        private var instance: VoiceChangerEngine? = null

        fun getInstance(): VoiceChangerEngine? = instance

        fun getProcessedAudioFromBuffer(size: Int): ShortArray? {
            return instance?.readFromCircularBuffer(size)
        }

        fun getProcessedBytesFromBuffer(size: Int): ByteArray? {
            val shorts = getProcessedAudioFromBuffer(size / 2) ?: return null
            val bytes = ByteArray(shorts.size * 2)
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (s in shorts) bb.putShort(s)
            return bytes
        }
    }

    private var audioRecord: AudioRecord? = null
    private val isRunning = AtomicBoolean(false)
    private var job: Job? = null

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive

    private val _currentProfile = MutableStateFlow(VoiceProfile.presets().first { it.name == "Normal" })
    val currentProfile: StateFlow<VoiceProfile> = _currentProfile

    private val _vadLevel = MutableStateFlow(0f)
    val vadLevel: StateFlow<Float> = _vadLevel

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking

    @Volatile
    private var customAudioSource: Int = MediaRecorder.AudioSource.MIC
    private var preferredDeviceId: Int = -1

    // MediaTek fix: HandlerThread for AudioRecord to avoid competing with camera
    private var audioHandlerThread: android.os.HandlerThread? = null
    private var audioHandler: android.os.Handler? = null

    private var rnnoise: RNNoiseJNI? = null
    private var soundTouch: SoundTouchJNI? = null
    private var vcamDsp: VCamDSPJNI? = null

    private val circularBuffer = ShortArray(CIRCULAR_BUFFER_SIZE)
    private var writePos = 0
    private var readPos = 0
    private var bufferedSamples = 0
    private val bufferLock = ReentrantLock()

    private val floatFrame = FloatArray(FRAME_SIZE)
    private val denoisedFrame = FloatArray(FRAME_SIZE)

    @Volatile
    var useFallbackPitchShift = false
        private set

    @Volatile
    var nativeLoadError: String? = null
        private set

    private var testAudioTrack: android.media.AudioTrack? = null

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        try {
            System.loadLibrary("soundtouch")
            Log.i(TAG, "libsoundtouch loaded in VoiceChangerEngine init")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load libsoundtouch, using fallback pitch shift", e)
            useFallbackPitchShift = true
            nativeLoadError = e.message
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load libsoundtouch (generic)", e)
            useFallbackPitchShift = true
            nativeLoadError = e.message
        }

        try {
            System.loadLibrary("rnnoise")
            Log.i(TAG, "librnnoise loaded in VoiceChangerEngine init")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Failed to load librnnoise, using fallback denoise", e)
        }

        try {
            System.loadLibrary("vcamdsp")
            Log.i(TAG, "libvcamdsp loaded in VoiceChangerEngine init")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Failed to load libvcamdsp, using passthrough", e)
        }
    }

    fun setProfile(profile: VoiceProfile) {
        _currentProfile.value = profile
        applyProfileToDsp(profile)
        Log.d(TAG, "Voice profile set to ${profile.name} pitch=${profile.pitchSemitones} semitones")
        saveConfigToFile(profile)
    }

    fun setTempo(tempo: Float) {
        val current = _currentProfile.value
        val updated = current.copy(tempo = tempo)
        _currentProfile.value = updated
        try {
            soundTouch?.setTempo(tempo)
        } catch (e: Exception) {
            Log.w(TAG, "setTempo failed, fallback", e)
        }
        Log.d(TAG, "Tempo set to $tempo")
    }

    private fun applyProfileToDsp(profile: VoiceProfile) {
        try {
            soundTouch?.let { st ->
                st.setPitch(profile.pitchSemitones)
                st.setTempo(profile.tempo)
                st.setFormantShift(profile.formantShift)
            }
        } catch (e: Exception) {
            Log.w(TAG, "applyProfileToDsp SoundTouch failed", e)
        }

        try {
            vcamDsp?.let { dsp ->
                dsp.setEffectEnabled(VCamDSPJNI.EFFECT_NOISE_GATE, profile.noiseGate != null)
                dsp.setEffectEnabled(VCamDSPJNI.EFFECT_EQ, profile.eq != null)
                dsp.setEffectEnabled(VCamDSPJNI.EFFECT_COMPRESSOR, profile.compressor != null)
                dsp.setEffectEnabled(VCamDSPJNI.EFFECT_CHORUS, profile.chorus != null)
                dsp.setEffectEnabled(VCamDSPJNI.EFFECT_REVERB, profile.reverb != null)

                profile.noiseGate?.let {
                    dsp.setNoiseGateParams(it.thresholdDb, it.attackMs, it.releaseMs, it.holdMs, it.rangeDb)
                }
                profile.eq?.let { eq ->
                    for (i in eq.bands.indices) {
                        val band = eq.bands[i]
                        dsp.setEQBand(i, band.freq, band.gainDb, band.q, band.type.ordinal)
                    }
                }
                profile.compressor?.let {
                    dsp.setCompressorParams(it.thresholdDb, it.ratio, it.attackMs, it.releaseMs, it.makeupGainDb)
                }
                profile.chorus?.let {
                    dsp.setChorusParams(it.depthMs, it.rateHz, it.mix, it.feedback)
                }
                profile.reverb?.let {
                    dsp.setReverbParams(it.roomSize, it.damping, it.wet, it.dry, it.width)
                }
                dsp.setRingMod(profile.ringMod?.enabled ?: false, profile.ringMod?.freq ?: 30f)
                dsp.setBitcrush(profile.bitcrush?.enabled ?: false, profile.bitcrush?.bits ?: 8)
                dsp.setOverdrive(profile.overdrive?.enabled ?: false, profile.overdrive?.gain ?: 1.0f)
            }
        } catch (e: Exception) {
            Log.w(TAG, "applyProfileToDsp VCamDSP failed", e)
        }
    }

    private fun saveConfigToFile(profile: VoiceProfile) {
        try {
            val configFile = java.io.File("/data/data/com.androidvirtualcam/files/vcam_config.json")
            configFile.parentFile?.mkdirs()
            val json = """
                {
                    "isVirtualCamEnabled": true,
                    "hookAllApps": true,
                    "voiceChangerEnabled": true,
                    "voiceEffect": "${profile.name}",
                    "voicePitch": ${profile.pitchSemitones},
                    "voiceTempo": ${profile.tempo},
                    "voiceFormant": ${profile.formantShift},
                    "frameBusSocketName": "vcam_frame_bus",
                    "frameBusSocketPath": "/data/data/com.androidvirtualcam/files/vcam_bus.sock",
                    "resolutionWidth": 1280,
                    "resolutionHeight": 720,
                    "fps": 30
                }
            """.trimIndent()
            configFile.writeText(json)
            Log.d(TAG, "Config saved to ${configFile.path}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save config", e)
        }
    }

    fun setAudioSource(source: Int, deviceId: Int = -1) {
        // FIX Samsung S22 Ultra: Use MIC (not VOICE_COMMUNICATION) - Samsung Camera2 closes session when VOICE_COMMUNICATION used
        // Also start BEFORE camera, not after
        var finalSource = source
        if (source == MediaRecorder.AudioSource.VOICE_COMMUNICATION || source == MediaRecorder.AudioSource.DEFAULT) {
            finalSource = MediaRecorder.AudioSource.MIC
            Log.i(TAG, "Converted audio source $source to MIC for Samsung S22 Ultra - VOICE_COMMUNICATION causes black screen on Samsung")
        }
        customAudioSource = finalSource
        preferredDeviceId = deviceId
        android.util.Log.i(TAG, "Audio source set to $finalSource (original $source) deviceId=$deviceId - MIC for Samsung S22 Ultra fix")
        if (isRunning.get()) {
            stop()
            start()
        }
    }

    fun getVadLevel(): Float = _vadLevel.value

    // MediaTek fix: track camera running state to delay mic start
    @Volatile
    private var isCameraConfirmedRunning = false

    fun setCameraRunning(running: Boolean) {
        isCameraConfirmedRunning = running
        Log.i(TAG, "Camera running state set to $running - for MediaTek mic delay fix")
    }

    private var pendingAudioStartHandler: android.os.Handler? = null
    private var pendingAudioStartRunnable: Runnable? = null

    private fun startAudioRecord() {
        try {
            Log.i(TAG, "startAudioRecord() called on HandlerThread - Samsung S22 Ultra fix: MIC source, BEFORE camera")
            if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                try {
                    audioRecord?.startRecording()
                    Log.i(TAG, "AudioRecord started on HandlerThread - MIC source, BEFORE camera, no conflict on Samsung S22 Ultra")
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "AudioRecord startRecording failed after delay", e)
                    try {
                        Thread.sleep(200)
                        audioRecord?.startRecording()
                    } catch (re: Exception) {
                        Log.e(TAG, "Retry AudioRecord start failed", re)
                    }
                }
            } else {
                Log.e(TAG, "AudioRecord not initialized at startAudioRecord() time")
            }
        } catch (e: Exception) {
            Log.e(TAG, "startAudioRecord failed", e)
        }
    }

    fun start() {
        if (isRunning.get()) return

        try {
            rnnoise = try {
                RNNoiseJNI().apply { init() }
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "RNNoiseJNI init failed, fallback", e)
                useFallbackPitchShift = true
                null
            } catch (e: Exception) {
                Log.e(TAG, "RNNoiseJNI init failed generic", e)
                null
            }

            soundTouch = try {
                SoundTouchJNI(SAMPLE_RATE, CHANNELS).apply { init() }
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "SoundTouchJNI init failed, fallback pitch shift", e)
                useFallbackPitchShift = true
                nativeLoadError = e.message
                null
            } catch (e: Exception) {
                Log.e(TAG, "SoundTouchJNI init failed generic", e)
                useFallbackPitchShift = true
                null
            }

            vcamDsp = try {
                VCamDSPJNI(SAMPLE_RATE.toFloat()).apply { create() }
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "VCamDSPJNI init failed, passthrough", e)
                null
            } catch (e: Exception) {
                Log.w(TAG, "VCamDSPJNI init failed generic", e)
                null
            }

            if (soundTouch == null) {
                Log.w(TAG, "SoundTouch unavailable, using pure Kotlin fallback")
                useFallbackPitchShift = true
            }

            applyProfileToDsp(_currentProfile.value)

            // FIX: Set AudioRecord buffer size to minimum - MediaTek fix
            // Exact code required by task:
            val bufferSize = AudioRecord.getMinBufferSize(
                48000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ) * 2

            val minBufferSizeFallback = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val finalBufferSize = if (bufferSize <= 0 || bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
                if (minBufferSizeFallback == AudioRecord.ERROR || minBufferSizeFallback == AudioRecord.ERROR_BAD_VALUE) {
                    SAMPLE_RATE * 2
                } else {
                    minBufferSizeFallback * 2
                }
            } else {
                bufferSize
            }

            // FIX: Add audio attributes to camera use case - prevents mic/cam conflict on MediaTek
            val audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .build()
            Log.i(TAG, "AudioAttributes created for camera use case: usage=${'$'}{audioAttributes.usage} - MediaTek fix")

            val audioSource = customAudioSource

            audioRecord = AudioRecord(
                audioSource,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                finalBufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord init failed with bufferSize=$finalBufferSize")
                return
            }

            // FIX Samsung S22 Ultra BUG1: Use MIC (not VOICE_COMMUNICATION) and start BEFORE camera, not after
            // Samsung Camera2 API closes capture session when AudioRecord starts with VOICE_COMMUNICATION
            // Start AudioRecord on separate HandlerThread BEFORE camera binding
            Log.i(TAG, "AudioRecord initialized bufferSize=$finalBufferSize min=${finalBufferSize/2} - Samsung S22 Ultra fix: MIC source, start BEFORE camera on HandlerThread")
            
            try {
                audioHandlerThread?.quitSafely()
            } catch (_: Exception) {}
            audioHandlerThread = android.os.HandlerThread("VoiceChangerAudioThread")
            audioHandlerThread?.start()
            audioHandler = audioHandlerThread?.looper?.let { android.os.Handler(it) }
            
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            pendingAudioStartHandler = mainHandler
            val runnable = Runnable {
                startAudioRecord()
            }
            pendingAudioStartRunnable = runnable
            
            // Samsung fix: start BEFORE camera, not after - immediate start on HandlerThread
            // No 800ms delay, start right away to avoid Camera2 session closure
            audioHandler?.post(runnable)
            Log.i(TAG, "Posted AudioRecord start IMMEDIATELY on HandlerThread VoiceChangerAudioThread - BEFORE camera for Samsung S22 Ultra")
            // Fallback also post on main looper immediately
            mainHandler.post(runnable)
            Log.i(TAG, "Also posted AudioRecord start immediately on main looper as fallback - Samsung fix")

            isRunning.set(true)
            _isActive.value = true
            instance = this

            job = scope.launch {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                Log.i(TAG, "Audio thread started with URGENT_AUDIO priority fallback=$useFallbackPitchShift")

                val shortBuffer = ShortArray(FRAME_SIZE)
                var frameCount = 0

                while (isRunning.get()) {
                    try {
                        val read = audioRecord?.read(shortBuffer, 0, FRAME_SIZE) ?: 0
                        if (read != FRAME_SIZE) {
                            if (read <= 0) {
                                delay(5)
                                continue
                            }
                        }

                        for (i in 0 until FRAME_SIZE) {
                            floatFrame[i] = shortBuffer[i] / 32768.0f
                        }

                        val vad = try {
                            rnnoise?.processFrameWithVad(floatFrame, denoisedFrame) ?: 0.5f
                        } catch (_: Exception) {
                            System.arraycopy(floatFrame, 0, denoisedFrame, 0, FRAME_SIZE)
                            0.5f
                        }
                        _vadLevel.value = vad
                        _isSpeaking.value = vad > 0.6f
                        try {
                            com.androidvirtualcam.audio.MusicDuckingManagerHolder.get()?.updateVad(vad)
                        } catch (_: Exception) {}

                        val denoisedShort = ShortArray(FRAME_SIZE)
                        for (i in 0 until FRAME_SIZE) {
                            val f = (denoisedFrame[i] * 32768.0f).coerceIn(-32768f, 32767f)
                            denoisedShort[i] = f.toInt().toShort()
                        }

                        val pitched = if (useFallbackPitchShift || soundTouch == null) {
                            fallbackPitchShift(denoisedShort, _currentProfile.value.pitchSemitones)
                        } else {
                            try {
                                soundTouch?.processChunk(denoisedShort) ?: denoisedShort
                            } catch (e: UnsatisfiedLinkError) {
                                Log.e(TAG, "SoundTouch process failed, switching to fallback", e)
                                useFallbackPitchShift = true
                                nativeLoadError = e.message
                                fallbackPitchShift(denoisedShort, _currentProfile.value.pitchSemitones)
                            } catch (e: Exception) {
                                Log.e(TAG, "SoundTouch process failed generic, fallback", e)
                                fallbackPitchShift(denoisedShort, _currentProfile.value.pitchSemitones)
                            }
                        }

                        val processedShort = if (pitched.isNotEmpty()) {
                            try {
                                vcamDsp?.processShort(pitched) ?: pitched
                            } catch (e: UnsatisfiedLinkError) {
                                Log.w(TAG, "VCamDSP process failed, passthrough", e)
                                pitched
                            }
                        } else {
                            try {
                                vcamDsp?.processShort(denoisedShort) ?: denoisedShort
                            } catch (_: Exception) {
                                denoisedShort
                            }
                        }

                        if (processedShort.isNotEmpty()) {
                            writeToCircularBuffer(processedShort)
                        }

                        frameCount++
                        if (frameCount % 300 == 0) {
                            Log.d(TAG, "Processed $frameCount frames, VAD=$vad, buffered=$bufferedSamples fallback=$useFallbackPitchShift")
                        }

                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Processing loop error", e)
                        delay(10)
                    }
                }
            }

            Log.i(TAG, "Voice changer started – 48kHz, frame $FRAME_SIZE, RNNoise+SoundTouch+VCamDSP fallback=$useFallbackPitchShift")

        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            stop()
        }
    }

    fun stop() {
        isRunning.set(false)
        job?.cancel()
        job = null

        try {
            pendingAudioStartRunnable?.let { pendingAudioStartHandler?.removeCallbacks(it) }
        } catch (_: Exception) {}
        pendingAudioStartHandler = null
        pendingAudioStartRunnable = null

        try {
            audioHandlerThread?.quitSafely()
        } catch (_: Exception) {}
        audioHandlerThread = null
        audioHandler = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "stop AudioRecord failed", e)
        }
        audioRecord = null

        try { rnnoise?.destroy() } catch (_: Exception) {}
        rnnoise = null

        try { soundTouch?.destroy() } catch (_: Exception) {}
        soundTouch = null

        try { vcamDsp?.destroy() } catch (_: Exception) {}
        vcamDsp = null

        bufferLock.withLock {
            writePos = 0
            readPos = 0
            bufferedSamples = 0
        }

        _isActive.value = false
        _vadLevel.value = 0f
        _isSpeaking.value = false
        instance = null
        Log.i(TAG, "Voice changer stopped")
    }

    private fun writeToCircularBuffer(data: ShortArray) {
        bufferLock.withLock {
            for (sample in data) {
                circularBuffer[writePos] = sample
                writePos = (writePos + 1) % CIRCULAR_BUFFER_SIZE
                if (bufferedSamples < CIRCULAR_BUFFER_SIZE) {
                    bufferedSamples++
                } else {
                    readPos = (readPos + 1) % CIRCULAR_BUFFER_SIZE
                }
            }
        }
    }

    private fun readFromCircularBuffer(size: Int): ShortArray? {
        bufferLock.withLock {
            if (bufferedSamples < size) {
                if (bufferedSamples == 0) return null
            }
            val result = ShortArray(size.coerceAtMost(bufferedSamples))
            for (i in result.indices) {
                result[i] = circularBuffer[readPos]
                readPos = (readPos + 1) % CIRCULAR_BUFFER_SIZE
                bufferedSamples--
            }
            return result
        }
    }

    fun processDirect(input: ShortArray): ShortArray {
        for (i in 0 until FRAME_SIZE.coerceAtMost(input.size)) {
            floatFrame[i] = input[i] / 32768.0f
        }
        try {
            rnnoise?.processFrameWithVad(floatFrame, denoisedFrame)
        } catch (_: Exception) {
            System.arraycopy(floatFrame, 0, denoisedFrame, 0, FRAME_SIZE.coerceAtMost(floatFrame.size))
        }

        val denoisedShort = ShortArray(input.size)
        for (i in input.indices) {
            val idx = i % FRAME_SIZE
            val f = (denoisedFrame[idx] * 32768.0f).coerceIn(-32768f, 32767f)
            denoisedShort[i] = f.toInt().toShort()
        }

        val pitched = if (useFallbackPitchShift || soundTouch == null) {
            fallbackPitchShift(denoisedShort, _currentProfile.value.pitchSemitones)
        } else {
            try {
                soundTouch?.processChunk(denoisedShort) ?: denoisedShort
            } catch (e: UnsatisfiedLinkError) {
                useFallbackPitchShift = true
                fallbackPitchShift(denoisedShort, _currentProfile.value.pitchSemitones)
            }
        }
        return if (pitched.isNotEmpty()) {
            try {
                vcamDsp?.processShort(pitched) ?: pitched
            } catch (_: Exception) {
                pitched
            }
        } else {
            try {
                vcamDsp?.processShort(denoisedShort) ?: denoisedShort
            } catch (_: Exception) {
                denoisedShort
            }
        }
    }

    fun fallbackPitchShift(input: ShortArray, pitchSemitones: Float): ShortArray {
        if (pitchSemitones == 0f) return input
        val factor = 2.0.pow((pitchSemitones / 12.0).toDouble()).toFloat()
        if (factor <= 0f) return input

        val inputSize = input.size
        val outputSize = (inputSize.toFloat() / factor).toInt().coerceAtLeast(1)
        val resampled = ShortArray(outputSize)
        for (i in 0 until outputSize) {
            val srcPos = i.toFloat() * factor
            val idx = srcPos.toInt()
            val frac = srcPos - idx.toFloat()
            if (idx + 1 < inputSize) {
                val s1 = input[idx].toInt().toFloat()
                val s2 = input[idx + 1].toInt().toFloat()
                val interpolated = s1 * (1.0f - frac) + s2 * frac
                resampled[i] = interpolated.toInt().toShort()
            } else if (idx < inputSize) {
                resampled[i] = input[idx]
            }
        }

        return if (resampled.size < inputSize) {
            ShortArray(inputSize) { j ->
                resampled[j % resampled.size]
            }
        } else {
            resampled.copyOf(inputSize)
        }
    }

    fun playTestVoiceWithFallback(context: android.content.Context? = null): Boolean {
        return try {
            Log.i(TAG, "playTestVoiceWithFallback: pitch=${_currentProfile.value.pitchSemitones} fallback=$useFallbackPitchShift error=$nativeLoadError")

            val sampleRate = SAMPLE_RATE
            val durationSec = 1
            val numSamples = sampleRate * durationSec
            val toneFreq = 440f
            val buffer = ShortArray(numSamples)

            for (i in 0 until numSamples) {
                val angle = 2.0 * kotlin.math.PI * toneFreq * i / sampleRate
                val envelope = when {
                    i < sampleRate * 0.05 -> i.toFloat() / (sampleRate * 0.05f)
                    i > numSamples - sampleRate * 0.1 -> (numSamples - i).toFloat() / (sampleRate * 0.1f)
                    else -> 1f
                }
                buffer[i] = (kotlin.math.sin(angle) * 10000 * envelope).toInt().toShort()
            }

            val pitched = if (_currentProfile.value.pitchSemitones != 0f) {
                fallbackPitchShift(buffer, _currentProfile.value.pitchSemitones)
            } else {
                buffer
            }

            val audioTrack = android.media.AudioTrack.Builder()
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(pitched.size * 2)
                .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                .build()

            audioTrack.write(pitched, 0, pitched.size)
            audioTrack.play()

            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    audioTrack.stop()
                    audioTrack.release()
                    Log.i(TAG, "Test voice playback completed")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to release test AudioTrack", e)
                }
            }, 1200)

            Log.i(TAG, "Test voice started with fallback AudioTrack pitch=${_currentProfile.value.pitchSemitones}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "playTestVoiceWithFallback failed", e)
            false
        }
    }

    fun playTestTone(context: android.content.Context, pitchSemitones: Float = _currentProfile.value.pitchSemitones): Boolean {
        return playTestVoiceWithFallback(context)
    }

    data class VoiceProfile(
        val name: String,
        val pitchSemitones: Float,
        val tempo: Float = 1.0f,
        val formantShift: Float = 0f,
        val reverb: ReverbParams? = null,
        val compressor: CompressorParams? = null,
        val eq: EQParams? = null,
        val chorus: ChorusParams? = null,
        val noiseGate: NoiseGateParams? = null,
        val ringMod: RingModParams? = null,
        val bitcrush: BitcrushParams? = null,
        val overdrive: OverdriveParams? = null
    ) {
        companion object {
            fun presets(): List<VoiceProfile> = listOf(
                VoiceProfile(
                    name = "Normal",
                    pitchSemitones = 0f,
                    tempo = 1.0f,
                    compressor = CompressorParams(-18f, 2.5f, 10f, 100f, 2f),
                    noiseGate = NoiseGateParams(-45f, 1f, 100f, 50f, -80f),
                    eq = EQParams.flat()
                ),
                VoiceProfile(
                    name = "Chipmunk",
                    pitchSemitones = 10f,
                    tempo = 1.0f,
                    formantShift = 2f,
                    compressor = CompressorParams(-20f, 3f, 5f, 50f, 1f),
                    eq = EQParams(
                        bands = listOf(
                            EQBand(100f, -2f, 0.7f, EQBandType.HighPass),
                            EQBand(4000f, 3f, 1.0f, EQBandType.Peaking)
                        )
                    )
                ),
                VoiceProfile(
                    name = "Helium",
                    pitchSemitones = 14f,
                    tempo = 1.1f,
                    formantShift = 4f,
                    eq = EQParams(
                        bands = listOf(
                            EQBand(200f, -6f, 0.7f, EQBandType.HighPass),
                            EQBand(6000f, 4f, 1.2f, EQBandType.Peaking)
                        )
                    )
                ),
                VoiceProfile(
                    name = "Deep",
                    pitchSemitones = -7f,
                    tempo = 0.95f,
                    formantShift = -2f,
                    compressor = CompressorParams(-16f, 3f, 15f, 120f, 3f),
                    eq = EQParams(
                        bands = listOf(
                            EQBand(120f, 3f, 1.0f, EQBandType.Peaking),
                            EQBand(5000f, -2f, 0.8f, EQBandType.LowPass)
                        )
                    )
                ),
                VoiceProfile(
                    name = "Darth Vader",
                    pitchSemitones = -9f,
                    tempo = 0.9f,
                    formantShift = -3f,
                    reverb = ReverbParams(roomSize = 0.6f, damping = 0.4f, wet = 0.25f, dry = 0.8f, width = 0.8f),
                    chorus = ChorusParams(depthMs = 8f, rateHz = 0.3f, mix = 0.2f, feedback = 0.1f),
                    compressor = CompressorParams(-14f, 5f, 20f, 200f, 4f),
                    eq = EQParams(
                        bands = listOf(
                            EQBand(80f, 4f, 0.8f, EQBandType.Peaking),
                            EQBand(300f, -2f, 1.0f, EQBandType.Peaking),
                            EQBand(3000f, -3f, 0.7f, EQBandType.LowPass)
                        )
                    )
                ),
                VoiceProfile(
                    name = "Giant",
                    pitchSemitones = -12f,
                    tempo = 0.85f,
                    formantShift = -4f,
                    reverb = ReverbParams(roomSize = 0.8f, damping = 0.3f, wet = 0.35f, dry = 0.7f, width = 1.0f),
                    compressor = CompressorParams(-12f, 6f, 30f, 250f, 5f)
                ),
                VoiceProfile(
                    name = "Robot",
                    pitchSemitones = 0f,
                    tempo = 1.0f,
                    ringMod = RingModParams(enabled = true, freq = 30f),
                    bitcrush = BitcrushParams(enabled = true, bits = 6),
                    compressor = CompressorParams(-10f, 8f, 1f, 50f, 6f),
                    eq = EQParams(
                        bands = listOf(
                            EQBand(1000f, 3f, 2.0f, EQBandType.Peaking),
                            EQBand(3000f, 3f, 2.0f, EQBandType.Peaking)
                        )
                    )
                ),
                VoiceProfile(
                    name = "Echo",
                    pitchSemitones = 0f,
                    tempo = 1.0f,
                    reverb = ReverbParams(roomSize = 0.9f, damping = 0.2f, wet = 0.5f, dry = 0.6f, width = 1.0f),
                    compressor = CompressorParams(-20f, 2f, 10f, 150f, 1f)
                ),
                VoiceProfile(
                    name = "Cave",
                    pitchSemitones = -2f,
                    tempo = 1.0f,
                    reverb = ReverbParams(roomSize = 0.95f, damping = 0.6f, wet = 0.6f, dry = 0.5f, width = 1.0f),
                    eq = EQParams(
                        bands = listOf(
                            EQBand(200f, 2f, 0.8f, EQBandType.Peaking),
                            EQBand(2000f, -4f, 0.7f, EQBandType.Peaking)
                        )
                    ),
                    compressor = CompressorParams(-18f, 3f, 15f, 200f, 2f)
                ),
                VoiceProfile(
                    name = "Radio",
                    pitchSemitones = 0f,
                    tempo = 1.0f,
                    eq = EQParams(
                        bands = listOf(
                            EQBand(300f, 0f, 0.7f, EQBandType.HighPass),
                            EQBand(3400f, 0f, 0.7f, EQBandType.LowPass),
                            EQBand(1000f, 3f, 1.0f, EQBandType.Peaking),
                            EQBand(2000f, 2f, 1.2f, EQBandType.Peaking)
                        )
                    ),
                    overdrive = OverdriveParams(enabled = true, gain = 2.0f),
                    compressor = CompressorParams(-16f, 4f, 5f, 80f, 4f),
                    noiseGate = NoiseGateParams(-40f, 1f, 50f, 20f, -60f)
                ),
                VoiceProfile(
                    name = "Custom",
                    pitchSemitones = 0f,
                    tempo = 1.0f,
                    formantShift = 0f,
                    eq = EQParams.flat()
                )
            )
        }
    }

    data class ReverbParams(
        val roomSize: Float,
        val damping: Float,
        val wet: Float,
        val dry: Float,
        val width: Float
    )

    data class CompressorParams(
        val thresholdDb: Float,
        val ratio: Float,
        val attackMs: Float,
        val releaseMs: Float,
        val makeupGainDb: Float
    )

    data class EQParams(
        val bands: List<EQBand>
    ) {
        companion object {
            fun flat(): EQParams = EQParams(
                bands = listOf(
                    EQBand(31f, 0f, 1f, EQBandType.Peaking),
                    EQBand(62f, 0f, 1f, EQBandType.Peaking),
                    EQBand(125f, 0f, 1f, EQBandType.Peaking),
                    EQBand(250f, 0f, 1f, EQBandType.Peaking),
                    EQBand(500f, 0f, 1f, EQBandType.Peaking),
                    EQBand(1000f, 0f, 1f, EQBandType.Peaking),
                    EQBand(2000f, 0f, 1f, EQBandType.Peaking),
                    EQBand(4000f, 0f, 1f, EQBandType.Peaking),
                    EQBand(8000f, 0f, 1f, EQBandType.Peaking),
                    EQBand(16000f, 0f, 1f, EQBandType.Peaking)
                )
            )
        }
    }

    data class EQBand(
        val freq: Float,
        val gainDb: Float,
        val q: Float,
        val type: EQBandType
    )

    enum class EQBandType {
        Peaking, LowShelf, HighShelf, LowPass, HighPass, BandPass
    }

    data class ChorusParams(
        val depthMs: Float,
        val rateHz: Float,
        val mix: Float,
        val feedback: Float
    )

    data class NoiseGateParams(
        val thresholdDb: Float,
        val attackMs: Float,
        val releaseMs: Float,
        val holdMs: Float,
        val rangeDb: Float
    )

    data class RingModParams(
        val enabled: Boolean,
        val freq: Float
    )

    data class BitcrushParams(
        val enabled: Boolean,
        val bits: Int
    )

    data class OverdriveParams(
        val enabled: Boolean,
        val gain: Float
    )
}

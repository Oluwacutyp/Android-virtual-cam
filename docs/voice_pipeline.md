# Module 3: Professional Voice Pipeline – Native DSP Chain

## Overview
Completely replaces pure Kotlin resampling VoiceChangerEngine with native DSP chain:
- RNNoise (noise suppression)
- SoundTouch (pitch/tempo/formant)
- VCamDSP (custom effects: reverb, compressor, EQ, chorus, noise gate, ring mod, bitcrush, overdrive)
- Voice cloning via ONNX speaker encoder

## Part A – JNI Setup

### CMakeLists.txt
`app/src/main/cpp/CMakeLists.txt` builds 3 shared libs:

- **librnnoise.so** from https://github.com/xiph/rnnoise source
  - Full source included in `src/main/cpp/rnnoise/` (denoise.c, celt_lpc.c, kiss_fft.c, nnet.c, etc., plus include/rnnoise.h)
  - For Android lightweight build, uses stub `src/main/cpp/rnnoise_stub/rnnoise_stub.c` + `rnnoise_data.c` (dummy model)
  - If real `rnnoise_data.c` exists (from `download_model.sh`), full RNN is used
  - Stub implements `rnnoise_create`, `rnnoise_process_frame`, `rnnoise_destroy`, `rnnoise_get_frame_size` with simple spectral gating

- **libsoundtouch.so** from SoundTouch source (https://www.surina.net/soundtouch/)
  - Full source included in `src/main/cpp/soundtouch/` (include/ + source/SoundTouch/)
  - Sources: AAFilter.cpp, FIFOSampleBuffer.cpp, FIRFilter.cpp, RateTransposer.cpp, SoundTouch.cpp, TDStretch.cpp, etc.
  - Built with `-DFLOAT_SAMPLES -DANDROID`

- **libvcamdsp.so** with custom effects in C++
  - `vcamdsp/reverb.cpp` – Schroeder reverb with 4 comb + 2 allpass filters
    - Comb delays: 29.7ms, 37.1ms, 41.1ms, 43.7ms (prime numbers), feedback 0.7-0.8
    - Allpass: 5ms and 1.7ms, feedback 0.7
    - Params: roomSize, damping, wet, dry, width
  - `vcamdsp/compressor.cpp` – RMS-based, attack/release/threshold/ratio/makeup
    - RMS envelope follower with exponential smoothing
    - Soft knee support, attack/release coeff via `exp(-1/(sr*ms/1000))`
  - `vcamdsp/eq.cpp` – 10-band parametric EQ, biquad filter per band, full state
    - Biquad types: Peaking, LowShelf, HighShelf, LowPass, HighPass, BandPass
    - Cookbook formulas for biquad coefficients
    - 10 bands default: 31,62,125,250,500,1k,2k,4k,8k,16k Hz
  - `vcamdsp/chorus.cpp` – LFO-modulated delay line
    - Max delay 50ms, base 20ms, depth 0-50ms, rate 0.05-10Hz, mix, feedback
    - Sine LFO, linear interpolation for fractional delay
  - `vcamdsp/noise_gate.cpp` – RMS threshold, attack/release/hold/range
    - RMS window 5ms, hold counter, gain smoothing
  - `vcamdsp/vcam_dsp.cpp` – wrapper combining all effects, plus ring mod, bitcrush, overdrive
    - Ring mod: sine LFO 30Hz default, multiply
    - Bitcrush: quantize to N bits
    - Overdrive: soft clipping via `s/(1+|s|)`

### Build
In `app/build.gradle.kts`:
```kotlin
android {
  defaultConfig {
    ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_shared") } }
  }
  externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
}
```

## Part B – JNI Bridges

### RNNoiseJNI.kt
- `init(): Boolean` – `rnnoise_create(nullptr)` -> handle as Long
- `processFrame(FloatArray): FloatArray` – 480 samples = 10ms @ 48kHz, returns denoised
- `processFrameWithVad(input, output): Float` – returns VAD prob 0..1
- `destroy()`
- Loads `librnnoise.so`, fallback to Kotlin simple gate if load fails
- C++: `jni/rnnoise_jni.cpp` implements `Java_com_androidvirtualcam_voice_RNNoiseJNI_*Native`

### SoundTouchJNI.kt
- `init(sampleRate, channels): Boolean` – creates SoundTouch wrapper
- `setPitch(semitones: Float)` – converts semitones to factor `2^(semitones/12)`, clamp 0.25..4.0, calls `setPitch(factor)`
- `setTempo(ratio: Float)`, `setRate(ratio: Float)`, `setFormantShift(shift: Float)` (placeholder, logs)
- `processChunk(ShortArray): ShortArray` – `putSamples` + `receiveSamples` loop, float<->short conversion
- `flush()`, `clear()`, `destroy()`
- C++: `jni/soundtouch_jni.cpp`

### VCamDSPJNI.kt
- `create(sampleRate): Boolean` – holds C++ `VCamDSP*` as Long
- One method per effect, stateful:
  - `setReverbParams(roomSize, damping, wet, dry, width)`
  - `setCompressorParams(threshold, ratio, attack, release, makeup)`
  - `setEQBand(index, freq, gainDb, q, type)`
  - `setChorusParams(depthMs, rateHz, mix, feedback)`
  - `setNoiseGateParams(threshold, attack, release, hold, range)`
  - `setRingMod(enabled, freq)`, `setBitcrush(enabled, bits)`, `setOverdrive(enabled, gain)`
  - `setEffectEnabled(effectType, enabled)` – 0=reverb,1=compressor,2=eq,3=chorus,4=noiseGate
- `process(FloatArray): FloatArray`, `processShort(ShortArray): ShortArray`, `reset()`, `destroy()`
- C++: `jni/vcamdsp_jni.cpp`

## Part C – VoiceChangerEngine.kt

Full pipeline on dedicated audio thread (priority `THREAD_PRIORITY_URGENT_AUDIO`):

```
AudioRecord (48kHz, mono, 480-sample frames)
  → RNNoiseJNI.processFrame() – denoise
  → SoundTouchJNI.processChunk() – pitch/tempo/formant
  → VCamDSPJNI effects chain – EQ, compressor, chorus, reverb, etc.
  → circular buffer (5 sec = 240k shorts) for Xposed AudioHooks consumption
```

- Thread: `Process.setThreadPriority(THREAD_PRIORITY_URGENT_AUDIO)` inside coroutine
- Circular buffer: `ShortArray(CIRCULAR_BUFFER_SIZE)` with `ReentrantLock`, `writePos`, `readPos`, `bufferedSamples`
- Static `getInstance()` and `getProcessedAudioFromBuffer(size)` for Xposed
- `setProfile(profile)` applies all DSP params to native chain

### Presets – complete DSP parameter sets

- **Normal**: pitch 0 semitones, compressor -18dB 2.5:1, noise gate -45dB, flat EQ
- **Chipmunk**: +10 semitones, formant +2, compressor -20dB, EQ high boost 4kHz
- **Helium**: +14 semitones, tempo 1.1, formant +4, high-pass 200Hz, boost 6kHz
- **Deep**: -7 semitones, tempo 0.95, formant -2, compressor -16dB, EQ 120Hz boost, 5kHz cut
- **Darth Vader**: -9 semitones, tempo 0.9, reverb room 0.6 wet 0.25, chorus 8ms 0.3Hz, compressor -14dB 5:1, EQ low boost
- **Giant**: -12 semitones, tempo 0.85, reverb room 0.8 wet 0.35, compressor -12dB 6:1
- **Robot**: ring mod 30Hz + bitcrush 6 bits, compressor -10dB 8:1, EQ peaks at 1kHz and 3kHz
- **Echo**: long reverb room 0.9 wet 0.5, compressor -20dB
- **Cave**: heavy reverb room 0.95 wet 0.6, pitch -2 semitones, EQ 200Hz boost, 2kHz cut
- **Radio**: EQ bandpass 300-3400Hz (high-pass 300Hz + low-pass 3400Hz + peaks at 1kHz/2kHz), overdrive gain 2.0, compressor -16dB 4:1, noise gate -40dB
- **Custom**: flat, user-adjustable

Each preset is `VoiceProfile` data class with all params, not just pitch.

## Part D – Voice Cloning

### VoiceCloneManager.kt

- `recordSample(minSeconds=10, maxSeconds=30): RecordingResult`
  - Records 16kHz mono via AudioRecord
  - Saves WAV, calculates SNR via frame energies (lowest 10% = noise, highest 10% = signal, SNR = 10*log10(signal/noise))
  - Checks SNR >20dB, rejects if too short or too noisy

- `extractEmbedding(file: File): FloatArray`
  - Uses speaker encoder ONNX model if available, else fallback MFCC statistical
  - ONNX model spec: `assets/voice_clone/speaker_encoder.onnx`, 256-dim d-vector, ECAPA-TDNN, input 40-dim mel, 25ms window, 10ms hop
  - Download flow documented in `assets/voice_clone/README.md`:
    - Primary: https://huggingface.co/speechbrain/spkrec-ecapa-voxceleb/resolve/main/embeddings_model.onnx
    - Alternative: Resemblyzer PyTorch -> ONNX via `torch.onnx.export`
  - Fallback: computes MFCC-like features (energy, ZCR, mel energies, DCT) + file hash deterministic random, L2 normalized, 256-dim

- Real-time conversion:
  - `loadProfile(profile: CloneProfile)` sets active embedding, derives `formantShift` and `eqAdjustments` from embedding
  - `processFrame(input: ShortArray): ShortArray` applies voice conversion using ONNX Runtime inference if available, else fallback pitch shift + EQ based on embedding
  - Full RVC would use `rvc_converter.onnx` + `hifigan_vocoder.onnx` (mel + embedding -> converted mel -> waveform)

- `CloneProfile`: data class with id, name, embedding: FloatArray, createdAt, sampleDurationMs, quality score, snrDb
  - `toMetadata()` -> `CloneProfileMetadata` serializable

- Profile management:
  - `saveProfile(profile)` – saves `.emb` binary (dim + floats) + `.json` metadata in `filesDir/voice_clone_profiles/`
  - `loadProfiles(): List<CloneProfile>` – loads all
  - `deleteProfile(id): Boolean`
  - `exportProfile(id, outputFile): Boolean` – creates `.vcprofile` zip containing `metadata.json` + `embedding.bin` + `info.txt`
  - `importProfile(zipFile): CloneProfile?` – extracts and saves

### ONNX Runtime

In `build.gradle.kts`:
```kotlin
implementation("com.microsoft.onnxruntime:onnxruntime:1.17.0")
implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.0")
```

Model assets: `app/src/main/assets/voice_clone/` contains `README.md`, `model_info.json`, placeholder for `speaker_encoder.onnx` (download at runtime if not in assets).

Quality score: `((snr-20)/40 *0.6 + norm/2 *0.4)*100`

## Integration with Xposed

`AudioHooks` now tries circular buffer first (`VoiceChangerEngine.getProcessedAudioFromBuffer`), fallback to `VoiceChanger.processShort` which itself tries direct engine processing then Kotlin fallback.

This provides system-wide voice changing for any app using AudioRecord.

## Testing

- Unit tests would need to mock native libs (fallback paths tested)
- Manual: start VoiceChangerEngine, set preset, speak, check logs for frame count and VAD

## Future

- Include real RNNoise model via download_model.sh and build full version
- Add RVC ONNX models for high-quality cloning
- UI for custom preset adjustment (sliders for each DSP param)
- Export/import .vcprofile via share intent

# Voice Clone Speaker Encoder Model

## Model Spec

- **Architecture**: ECAPA-TDNN or Resemblyzer d-vector
- **Input**: 16kHz mono PCM, 40-dim mel spectrogram, 25ms window, 10ms hop, 1-10 seconds utterance
- **Output**: 256-dim L2-normalized embedding (float32)
- **File**: `speaker_encoder.onnx` (~5-20 MB)
- **Alternative**: `dvector.tflite` for TensorFlow Lite

## Pretrained Models

### Option 1: SpeechBrain ECAPA-TDNN (recommended)
- **URL**: https://huggingface.co/speechbrain/spkrec-ecapa-voxceleb/resolve/main/embeddings_model.onnx
- **Size**: ~20 MB
- **License**: Apache 2.0
- **Conversion**: Already ONNX, just download and place as `speaker_encoder.onnx`

### Option 2: Resemblyzer
- **URL**: https://github.com/resemble-ai/Resemblyzer
- **Original**: PyTorch model, need conversion
- **Conversion steps**:
  ```python
  import torch
  from resemblyzer import VoiceEncoder
  encoder = VoiceEncoder("cpu")
  dummy_input = torch.randn(1, 160, 40) # [batch, time, mel_bins]
  torch.onnx.export(
      encoder,
      dummy_input,
      "speaker_encoder.onnx",
      input_names=["mel"],
      output_names=["embedding"],
      dynamic_axes={"mel": {0: "batch", 1: "time"}},
      opset_version=14
  )
  ```

### Option 3: ONNX Runtime Model Zoo
- Search for speaker recognition models

## Download Flow (implemented in VoiceCloneManager)

1. On first launch, check if `filesDir/speaker_encoder.onnx` exists
2. If not, try to copy from `assets/voice_clone/speaker_encoder.onnx`
3. If not in assets, download from `https://huggingface.co/speechbrain/spkrec-ecapa-voxceleb/resolve/main/embeddings_model.onnx`
4. Show progress notification, save to `filesDir/speaker_encoder.onnx`
5. Initialize ONNX Runtime session via `OrtEnvironment.createSession(modelPath)`

If model not available, fallback to Kotlin MFCC statistical embedding (see `VoiceCloneManager.extractEmbeddingFallback()`)

## Real-time Voice Conversion

For full voice cloning (not just embedding):

- **Model**: RVC (Retrieval-based Voice Conversion) or YourTTS
- **Input**: source mel + target embedding (256-dim)
- **Output**: converted mel -> HiFi-GAN vocoder -> waveform
- **ONNX models**:
  - `rvc_converter.onnx` – mel + embedding -> converted mel
  - `hifigan_vocoder.onnx` – mel -> waveform
- **Placeholder**: Currently uses formant shifting + EQ based on embedding for low-latency MVP

## File Structure

```
assets/voice_clone/
  speaker_encoder.onnx          # 256-dim d-vector encoder (required for high quality)
  rvc_converter.onnx            # optional, for full conversion
  hifigan_vocoder.onnx          # optional, mel->waveform
  README.md                     # this file
  model_info.json               # model metadata
```

## Model Info JSON (model_info.json)

```json
{
  "model_name": "ecapa-tdnn",
  "version": "1.0",
  "embedding_dim": 256,
  "sample_rate": 16000,
  "mel_bins": 40,
  "window_ms": 25,
  "hop_ms": 10,
  "training_data": "VoxCeleb1+2",
  "license": "Apache 2.0",
  "download_url": "https://huggingface.co/speechbrain/spkrec-ecapa-voxceleb/resolve/main/embeddings_model.onnx"
}
```

## Quality Requirements

- Recording SNR > 20dB (checked in `recordSample()`)
- Duration >= 10 seconds
- Single speaker, no overlapping speech
- Quiet environment

## Testing

Use `VoiceCloneManager.recordSample()` to test recording, then `extractEmbedding()` to verify embedding norm ~1.0

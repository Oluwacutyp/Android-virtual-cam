# Background Segmentation Models

This app uses MediaPipe Selfie Segmentation for background replacement without green screen.

## Required models (place in `app/src/main/assets/`):

1. **selfie_segmenter.tflite** – MediaPipe Selfie Segmentation model (256x256, general)
   - Download: https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_segmenter/float16/latest/selfie_segmenter.tflite
   - Or: https://developers.google.com/mediapipe/solutions/vision/image_segmenter#models

2. **selfie_multiclass_256x256.tflite** – Multi-class selfie segmentation (6 classes: background, hair, body-skin, face-skin, clothes, others)
   - Download: https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_multiclass_256x256/float32/latest/selfie_multiclass_256x256.tflite

Place either model in assets. The engine will try multiclass first, then fallback to selfie_segmenter, then fallback to ML Kit Subject Segmentation.

## ML Kit fallback:
No model needed – ML Kit downloads model via Play Services automatically.
- Subject Segmentation: `com.google.mlkit:subject-segmentation:16.0.0-beta1`
- Selfie Segmentation: `com.google.mlkit:segmentation-selfie:16.0.0-beta6`

## GPU delegate:
MediaPipe uses `Delegate.GPU` for 30fps performance. If GPU delegate fails, it automatically falls back to CPU, then to ML Kit.

## Background options:
The segmentation mask (alpha as GL texture, no CPU readback) feeds into `BackgroundReplaceNode`:
- solid color
- image
- video
- gaussian blur of original
- custom GL texture

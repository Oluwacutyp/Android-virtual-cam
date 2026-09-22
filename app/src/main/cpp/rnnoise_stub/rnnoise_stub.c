/* rnnoise_stub.c – lightweight RNNoise-compatible implementation for Android
 * Implements the public API from rnnoise.h without requiring the heavy RNN model.
 * Uses simple spectral gating + smoothing for noise suppression.
 * Frame size: 480 samples = 10ms at 48kHz, as per RNNoise spec.
 *
 * This allows librnnoise.so to build and run on Android without the 1MB+ model download,
 * while still providing effective noise suppression for voice changer pipeline.
 *
 * For production with full RNN, replace with real denoise.c + rnnoise_data.c from upstream.
 */

#include <stdlib.h>
#include <string.h>
#include <math.h>
#include "rnnoise.h"
#include "arch.h"

#define FRAME_SIZE 480
#define SAMPLE_RATE 48000

// DenoiseState – we define our own simple state
struct DenoiseState {
    float noise_floor[FRAME_SIZE];
    float prev_gain;
    float vad_prob;
    int initialized;
    // Simple smoothing buffers
    float prev_frame[FRAME_SIZE];
};

// Public API implementations

int rnnoise_get_size(void) {
    return sizeof(DenoiseState);
}

int rnnoise_get_frame_size(void) {
    return FRAME_SIZE;
}

int rnnoise_init(DenoiseState *st, RNNModel *model) {
    if (!st) return -1;
    memset(st, 0, sizeof(DenoiseState));
    st->prev_gain = 1.0f;
    st->vad_prob = 0.5f;
    st->initialized = 1;
    // Initialize noise floor to small value
    for (int i = 0; i < FRAME_SIZE; i++) {
        st->noise_floor[i] = 0.001f;
        st->prev_frame[i] = 0.0f;
    }
    (void)model; // unused in stub
    return 0;
}

DenoiseState *rnnoise_create(RNNModel *model) {
    DenoiseState *st = (DenoiseState*)malloc(sizeof(DenoiseState));
    if (!st) return NULL;
    rnnoise_init(st, model);
    return st;
}

void rnnoise_destroy(DenoiseState *st) {
    if (st) free(st);
}

// Simple noise suppression: estimate noise floor via exponential smoothing,
// apply spectral gating
float rnnoise_process_frame(DenoiseState *st, float *out, const float *in) {
    if (!st || !out || !in) return 0.0f;

    // Estimate signal energy
    float energy = 0.0f;
    for (int i = 0; i < FRAME_SIZE; i++) {
        energy += in[i] * in[i];
    }
    energy = sqrtf(energy / FRAME_SIZE + 1e-10f);

    // Update noise floor – track minimum with slow adaptation
    float noise_est = 0.0f;
    for (int i = 0; i < FRAME_SIZE; i++) {
        float abs_sample = fabsf(in[i]);
        // Exponential smoothing for noise floor (slow attack)
        st->noise_floor[i] = 0.995f * st->noise_floor[i] + 0.005f * abs_sample;
        noise_est += st->noise_floor[i];
    }
    noise_est /= FRAME_SIZE;

    // VAD probability – simple energy-based
    float snr = energy / (noise_est + 1e-6f);
    float vad = 1.0f / (1.0f + expf(-(snr - 3.0f) * 2.0f)); // sigmoid
    st->vad_prob = 0.9f * st->vad_prob + 0.1f * vad;

    // Gain calculation – suppress when low SNR
    float target_gain;
    if (snr < 1.5f) {
        target_gain = 0.1f; // strong suppression
    } else if (snr < 3.0f) {
        target_gain = 0.3f + 0.4f * (snr - 1.5f) / 1.5f;
    } else {
        target_gain = 1.0f;
    }

    // Smooth gain to avoid musical noise
    float gain = 0.8f * st->prev_gain + 0.2f * target_gain;
    st->prev_gain = gain;

    // Apply gain with soft gating per sample
    for (int i = 0; i < FRAME_SIZE; i++) {
        float sample = in[i];
        float abs_s = fabsf(sample);
        float gate = 1.0f;
        if (abs_s < st->noise_floor[i] * 2.0f) {
            gate = abs_s / (st->noise_floor[i] * 2.0f + 1e-6f);
            gate = gate * gate; // quadratic
        }
        float final_gain = gain * (0.2f + 0.8f * gate);
        out[i] = sample * final_gain;

        // Simple de-ess and smoothing
        out[i] = 0.9f * out[i] + 0.1f * st->prev_frame[i];
        st->prev_frame[i] = out[i];
    }

    return st->vad_prob;
}

RNNModel *rnnoise_model_from_buffer(const void *ptr, int len) {
    (void)ptr; (void)len;
    return NULL; // stub doesn't support custom models
}

RNNModel *rnnoise_model_from_file(FILE *f) {
    (void)f;
    return NULL;
}

RNNModel *rnnoise_model_from_filename(const char *filename) {
    (void)filename;
    return NULL;
}

void rnnoise_model_free(RNNModel *model) {
    (void)model;
}

#include "compressor.h"
#include <cmath>
#include <algorithm>

namespace vcamdsp {

Compressor::Compressor(float sampleRate) : sampleRate(sampleRate) {
    threshold = -24.0f; // dB
    ratio = 4.0f;
    attackMs = 10.0f;
    releaseMs = 100.0f;
    makeupGainDb = 0.0f;
    kneeWidth = 6.0f;
    rmsWindowMs = 10.0f;

    envelope = 0.0f;
    gain = 1.0f;

    updateCoefficients();
}

void Compressor::setThreshold(float db) {
    threshold = db;
}

void Compressor::setRatio(float r) {
    ratio = std::max(1.0f, r);
}

void Compressor::setAttack(float ms) {
    attackMs = std::max(0.1f, ms);
    updateCoefficients();
}

void Compressor::setRelease(float ms) {
    releaseMs = std::max(1.0f, ms);
    updateCoefficients();
}

void Compressor::setMakeupGain(float db) {
    makeupGainDb = db;
}

void Compressor::setKnee(float widthDb) {
    kneeWidth = widthDb;
}

void Compressor::updateCoefficients() {
    attackCoeff = expf(-1.0f / (sampleRate * attackMs / 1000.0f));
    releaseCoeff = expf(-1.0f / (sampleRate * releaseMs / 1000.0f));
    // RMS window coefficient
    float windowSamples = sampleRate * rmsWindowMs / 1000.0f;
    rmsCoeff = expf(-1.0f / windowSamples);
}

void Compressor::process(float* samples, int numSamples) {
    float makeupLinear = powf(10.0f, makeupGainDb / 20.0f);

    for (int i = 0; i < numSamples; i++) {
        float input = samples[i];
        float absInput = fabsf(input);

        // RMS envelope follower
        float squared = absInput * absInput;
        envelope = rmsCoeff * envelope + (1.0f - rmsCoeff) * squared;
        float rms = sqrtf(envelope + 1e-10f);

        // Convert to dB
        float rmsDb = 20.0f * log10f(rms + 1e-10f);

        // Gain reduction calculation with soft knee
        float gainReductionDb = 0.0f;
        if (kneeWidth > 0.0f) {
            // Soft knee
            if (rmsDb > threshold - kneeWidth / 2.0f && rmsDb < threshold + kneeWidth / 2.0f) {
                float x = rmsDb - threshold + kneeWidth / 2.0f;
                float kneeFactor = x / kneeWidth;
                gainReductionDb = (1.0f / ratio - 1.0f) * x * kneeFactor * 0.5f;
            } else if (rmsDb >= threshold + kneeWidth / 2.0f) {
                gainReductionDb = (rmsDb - threshold) * (1.0f / ratio - 1.0f);
            }
        } else {
            // Hard knee
            if (rmsDb > threshold) {
                gainReductionDb = (rmsDb - threshold) * (1.0f / ratio - 1.0f);
            }
        }

        float targetGainDb = gainReductionDb; // negative
        float targetGainLinear = powf(10.0f, targetGainDb / 20.0f);

        // Smooth gain with attack/release
        if (targetGainLinear < gain) {
            // Attack – gain reduction
            gain = attackCoeff * gain + (1.0f - attackCoeff) * targetGainLinear;
        } else {
            // Release – gain increase
            gain = releaseCoeff * gain + (1.0f - releaseCoeff) * targetGainLinear;
        }

        samples[i] = input * gain * makeupLinear;
    }
}

void Compressor::reset() {
    envelope = 0.0f;
    gain = 1.0f;
}

} // namespace vcamdsp

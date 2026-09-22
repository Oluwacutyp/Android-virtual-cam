#include "noise_gate.h"
#include <cmath>
#include <algorithm>

namespace vcamdsp {

NoiseGate::NoiseGate(float sampleRate) : sampleRate(sampleRate) {
    thresholdDb = -40.0f;
    attackMs = 1.0f;
    releaseMs = 100.0f;
    holdMs = 50.0f;
    rangeDb = -80.0f;

    envelope = 0.0f;
    gain = 0.0f;
    holdCounter = 0;

    updateCoefficients();
}

void NoiseGate::setThreshold(float db) {
    thresholdDb = db;
}

void NoiseGate::setAttack(float ms) {
    attackMs = std::max(0.1f, ms);
    updateCoefficients();
}

void NoiseGate::setRelease(float ms) {
    releaseMs = std::max(1.0f, ms);
    updateCoefficients();
}

void NoiseGate::setHold(float ms) {
    holdMs = ms;
    holdSamples = static_cast<int>(ms * sampleRate / 1000.0f);
}

void NoiseGate::setRange(float db) {
    rangeDb = db;
}

void NoiseGate::updateCoefficients() {
    attackCoeff = expf(-1.0f / (sampleRate * attackMs / 1000.0f));
    releaseCoeff = expf(-1.0f / (sampleRate * releaseMs / 1000.0f));
    holdSamples = static_cast<int>(holdMs * sampleRate / 1000.0f);
    // RMS window
    rmsCoeff = expf(-1.0f / (sampleRate * 5.0f / 1000.0f)); // 5ms window
}

void NoiseGate::process(float* samples, int numSamples) {
    float thresholdLinear = powf(10.0f, thresholdDb / 20.0f);
    float rangeLinear = powf(10.0f, rangeDb / 20.0f);

    for (int i = 0; i < numSamples; i++) {
        float input = samples[i];
        float absInput = fabsf(input);

        // RMS envelope
        float squared = absInput * absInput;
        envelope = rmsCoeff * envelope + (1.0f - rmsCoeff) * squared;
        float rms = sqrtf(envelope + 1e-10f);

        bool aboveThreshold = rms > thresholdLinear;

        if (aboveThreshold) {
            holdCounter = holdSamples;
        } else {
            if (holdCounter > 0) holdCounter--;
        }

        bool gateOpen = aboveThreshold || holdCounter > 0;
        float targetGain = gateOpen ? 1.0f : rangeLinear;

        if (targetGain > gain) {
            // Attack – open quickly
            gain = attackCoeff * gain + (1.0f - attackCoeff) * targetGain;
        } else {
            // Release – close slowly
            gain = releaseCoeff * gain + (1.0f - releaseCoeff) * targetGain;
        }

        samples[i] = input * gain;
    }
}

void NoiseGate::reset() {
    envelope = 0.0f;
    gain = 0.0f;
    holdCounter = 0;
}

} // namespace vcamdsp

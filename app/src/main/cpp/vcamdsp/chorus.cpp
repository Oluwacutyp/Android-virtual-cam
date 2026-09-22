#include "chorus.h"
#include <cmath>
#include <algorithm>
#include <cstring>

namespace vcamdsp {

Chorus::Chorus(float sampleRate) : sampleRate(sampleRate) {
    // Max delay 50ms
    maxDelaySamples = static_cast<int>(0.05f * sampleRate);
    bufferSize = maxDelaySamples * 2;
    buffer = new float[bufferSize];
    memset(buffer, 0, sizeof(float) * bufferSize);
    writePos = 0;

    depth = 0.005f; // 5ms
    rate = 0.8f; // Hz
    mix = 0.5f;
    feedback = 0.2f;
    lfoPhase = 0.0f;
}

Chorus::~Chorus() {
    delete[] buffer;
}

void Chorus::setDepth(float ms) {
    // ms in seconds? param is depth in ms, convert to samples
    depth = std::clamp(ms / 1000.0f, 0.0f, 0.05f);
}

void Chorus::setRate(float hz) {
    rate = std::clamp(hz, 0.05f, 10.0f);
}

void Chorus::setMix(float m) {
    mix = std::clamp(m, 0.0f, 1.0f);
}

void Chorus::setFeedback(float fb) {
    feedback = std::clamp(fb, 0.0f, 0.95f);
}

void Chorus::process(float* samples, int numSamples) {
    float baseDelay = 0.02f; // 20ms base
    float depthSamples = depth * sampleRate;
    float baseDelaySamples = baseDelay * sampleRate;

    for (int i = 0; i < numSamples; i++) {
        // LFO – sine
        lfoPhase += 2.0f * M_PI * rate / sampleRate;
        if (lfoPhase > 2.0f * M_PI) lfoPhase -= 2.0f * M_PI;
        float lfo = sinf(lfoPhase); // -1..1

        float modDelay = baseDelaySamples + lfo * depthSamples;
        // Ensure within buffer
        modDelay = std::clamp(modDelay, 1.0f, static_cast<float>(maxDelaySamples - 1));

        // Read with linear interpolation
        float readPos = static_cast<float>(writePos) - modDelay;
        while (readPos < 0) readPos += bufferSize;
        int readPosInt = static_cast<int>(readPos);
        float frac = readPos - readPosInt;
        int readPosNext = (readPosInt + 1) % bufferSize;
        float delayed = buffer[readPosInt] * (1.0f - frac) + buffer[readPosNext] * frac;

        float input = samples[i];
        float output = input * (1.0f - mix) + delayed * mix;

        // Write with feedback
        buffer[writePos] = input + delayed * feedback;

        samples[i] = output;

        writePos = (writePos + 1) % bufferSize;
    }
}

void Chorus::reset() {
    memset(buffer, 0, sizeof(float) * bufferSize);
    writePos = 0;
    lfoPhase = 0.0f;
}

} // namespace vcamdsp

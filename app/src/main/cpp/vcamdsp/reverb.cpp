#include "reverb.h"
#include <cstring>
#include <cmath>
#include <algorithm>

namespace vcamdsp {

Reverb::Reverb(float sampleRate) : sampleRate(sampleRate) {
    // Schroeder reverb: 4 comb filters in parallel, 2 allpass in series
    // Comb delays tuned to prime numbers for 48kHz
    const int combDelays[4] = {
        static_cast<int>(0.0297f * sampleRate), // 29.7ms
        static_cast<int>(0.0371f * sampleRate), // 37.1ms
        static_cast<int>(0.0411f * sampleRate), // 41.1ms
        static_cast<int>(0.0437f * sampleRate)  // 43.7ms
    };
    const float combFeedback[4] = {0.773f, 0.802f, 0.753f, 0.733f};

    for (int i = 0; i < 4; i++) {
        comb[i].setDelay(combDelays[i]);
        comb[i].setFeedback(combFeedback[i]);
    }

    // Allpass: 5ms and 1.7ms
    allpass[0].setDelay(static_cast<int>(0.005f * sampleRate));
    allpass[0].setFeedback(0.7f);
    allpass[1].setDelay(static_cast<int>(0.0017f * sampleRate));
    allpass[1].setFeedback(0.7f);

    setRoomSize(0.5f);
    setDamping(0.5f);
    setWet(0.33f);
    setDry(0.4f);
    setWidth(1.0f);
}

void Reverb::setRoomSize(float value) {
    roomSize = std::clamp(value, 0.0f, 1.0f);
    // Map room size to feedback scaling
    float scale = 0.28f + roomSize * 0.7f;
    for (int i = 0; i < 4; i++) {
        // Keep original feedback but scale by room size
        comb[i].setFeedback(0.7f + roomSize * 0.28f);
    }
}

void Reverb::setDamping(float value) {
    damping = std::clamp(value, 0.0f, 1.0f);
    for (int i = 0; i < 4; i++) {
        comb[i].setDamping(damping);
    }
}

void Reverb::setWet(float value) {
    wet = std::clamp(value, 0.0f, 1.0f);
}

void Reverb::setDry(float value) {
    dry = std::clamp(value, 0.0f, 1.0f);
}

void Reverb::setWidth(float value) {
    width = std::clamp(value, 0.0f, 1.0f);
}

void Reverb::process(float* samples, int numSamples) {
    for (int i = 0; i < numSamples; i++) {
        float input = samples[i];
        float combSum = 0.0f;
        for (int c = 0; c < 4; c++) {
            combSum += comb[c].process(input);
        }
        float out = allpass[0].process(combSum);
        out = allpass[1].process(out);

        // Mix wet/dry
        samples[i] = input * dry + out * wet;
    }
}

void Reverb::reset() {
    for (int i = 0; i < 4; i++) comb[i].reset();
    for (int i = 0; i < 2; i++) allpass[i].reset();
}

// Comb filter
Reverb::CombFilter::CombFilter() : buffer(nullptr), bufferSize(0), writePos(0), feedback(0.7f), damping(0.5f), filterStore(0.0f) {}

Reverb::CombFilter::~CombFilter() {
    delete[] buffer;
}

void Reverb::CombFilter::setDelay(int delaySamples) {
    delete[] buffer;
    bufferSize = delaySamples + 1;
    buffer = new float[bufferSize];
    memset(buffer, 0, sizeof(float) * bufferSize);
    writePos = 0;
}

void Reverb::CombFilter::setFeedback(float fb) {
    feedback = fb;
}

void Reverb::CombFilter::setDamping(float d) {
    damping = d;
}

float Reverb::CombFilter::process(float input) {
    if (!buffer) return input;
    float output = buffer[writePos];
    // Damping lowpass on feedback
    filterStore = output * (1.0f - damping) + filterStore * damping;
    buffer[writePos] = input + filterStore * feedback;
    writePos = (writePos + 1) % bufferSize;
    return output;
}

void Reverb::CombFilter::reset() {
    if (buffer) memset(buffer, 0, sizeof(float) * bufferSize);
    filterStore = 0.0f;
}

// Allpass filter
Reverb::AllpassFilter::AllpassFilter() : buffer(nullptr), bufferSize(0), writePos(0), feedback(0.7f) {}

Reverb::AllpassFilter::~AllpassFilter() {
    delete[] buffer;
}

void Reverb::AllpassFilter::setDelay(int delaySamples) {
    delete[] buffer;
    bufferSize = delaySamples + 1;
    buffer = new float[bufferSize];
    memset(buffer, 0, sizeof(float) * bufferSize);
    writePos = 0;
}

void Reverb::AllpassFilter::setFeedback(float fb) {
    feedback = fb;
}

float Reverb::AllpassFilter::process(float input) {
    if (!buffer) return input;
    float bufout = buffer[writePos];
    float output = -input + bufout;
    buffer[writePos] = input + bufout * feedback;
    writePos = (writePos + 1) % bufferSize;
    return output;
}

void Reverb::AllpassFilter::reset() {
    if (buffer) memset(buffer, 0, sizeof(float) * bufferSize);
}

} // namespace vcamdsp

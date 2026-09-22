#pragma once

namespace vcamdsp {

class NoiseGate {
public:
    explicit NoiseGate(float sampleRate = 48000.0f);
    ~NoiseGate() = default;

    void setThreshold(float db); // e.g., -40 dB
    void setAttack(float ms);
    void setRelease(float ms);
    void setHold(float ms);
    void setRange(float db); // how much to attenuate when closed, e.g., -80 dB

    void process(float* samples, int numSamples);
    void reset();

private:
    void updateCoefficients();

    float sampleRate;
    float thresholdDb;
    float attackMs;
    float releaseMs;
    float holdMs;
    float rangeDb;

    float attackCoeff;
    float releaseCoeff;
    float rmsCoeff;
    int holdSamples;

    float envelope;
    float gain;
    int holdCounter;
};

} // namespace vcamdsp

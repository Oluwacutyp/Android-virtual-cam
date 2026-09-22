#pragma once

namespace vcamdsp {

class Compressor {
public:
    explicit Compressor(float sampleRate = 48000.0f);
    ~Compressor() = default;

    void setThreshold(float db);   // e.g., -24 dB
    void setRatio(float ratio);    // e.g., 4:1
    void setAttack(float ms);      // attack time in ms
    void setRelease(float ms);     // release time in ms
    void setMakeupGain(float db);  // makeup gain in dB
    void setKnee(float widthDb);   // knee width in dB

    void process(float* samples, int numSamples);
    void reset();

private:
    void updateCoefficients();

    float sampleRate;
    float threshold;
    float ratio;
    float attackMs;
    float releaseMs;
    float makeupGainDb;
    float kneeWidth;
    float rmsWindowMs;

    float attackCoeff;
    float releaseCoeff;
    float rmsCoeff;

    float envelope;
    float gain;
};

} // namespace vcamdsp

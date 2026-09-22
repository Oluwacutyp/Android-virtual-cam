#pragma once

namespace vcamdsp {

class Chorus {
public:
    explicit Chorus(float sampleRate = 48000.0f);
    ~Chorus();

    void setDepth(float ms);    // depth in ms (0..50)
    void setRate(float hz);     // LFO rate in Hz
    void setMix(float mix);     // 0..1 wet mix
    void setFeedback(float fb); // 0..0.95

    void process(float* samples, int numSamples);
    void reset();

private:
    float sampleRate;
    float* buffer;
    int bufferSize;
    int maxDelaySamples;
    int writePos;

    float depth;    // seconds
    float rate;     // Hz
    float mix;
    float feedback;
    float lfoPhase;
};

} // namespace vcamdsp

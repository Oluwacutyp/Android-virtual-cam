#pragma once

namespace vcamdsp {

enum class BandType {
    Peaking,
    LowShelf,
    HighShelf,
    LowPass,
    HighPass,
    BandPass
};

class Biquad {
public:
    Biquad();
    void setSampleRate(float sr);
    void setPeaking(float freq, float q, float gainDb);
    void setLowShelf(float freq, float q, float gainDb);
    void setHighShelf(float freq, float q, float gainDb);
    void setLowPass(float freq, float q);
    void setHighPass(float freq, float q);
    void setBandPass(float freq, float q);
    float process(float input);
    void reset();
private:
    float sampleRate;
    float b0, b1, b2, a1, a2;
    float z1, z2;
};

class EQ {
public:
    explicit EQ(float sampleRate = 48000.0f);
    ~EQ() = default;

    void setBand(int index, float freq, float gainDb, float q, BandType type);
    void setBandGain(int index, float gainDb);
    void setBandEnabled(int index, bool enabled);

    void process(float* samples, int numSamples);
    void reset();

private:
    struct Band {
        float frequency;
        float gainDb;
        float q;
        BandType type;
        Biquad biquad;
        bool enabled;
    };

    float sampleRate;
    Band bands[10];
};

} // namespace vcamdsp

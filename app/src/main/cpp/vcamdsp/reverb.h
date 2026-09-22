#pragma once

namespace vcamdsp {

class Reverb {
public:
    explicit Reverb(float sampleRate = 48000.0f);
    ~Reverb() = default;

    void setRoomSize(float value); // 0..1
    void setDamping(float value);  // 0..1
    void setWet(float value);      // 0..1
    void setDry(float value);      // 0..1
    void setWidth(float value);    // 0..1

    void process(float* samples, int numSamples);
    void reset();

private:
    class CombFilter {
    public:
        CombFilter();
        ~CombFilter();
        void setDelay(int delaySamples);
        void setFeedback(float fb);
        void setDamping(float d);
        float process(float input);
        void reset();
    private:
        float* buffer;
        int bufferSize;
        int writePos;
        float feedback;
        float damping;
        float filterStore;
    };

    class AllpassFilter {
    public:
        AllpassFilter();
        ~AllpassFilter();
        void setDelay(int delaySamples);
        void setFeedback(float fb);
        float process(float input);
        void reset();
    private:
        float* buffer;
        int bufferSize;
        int writePos;
        float feedback;
    };

    float sampleRate;
    CombFilter comb[4];
    AllpassFilter allpass[2];
    float roomSize, damping, wet, dry, width;
};

} // namespace vcamdsp

#pragma once

#include "reverb.h"
#include "compressor.h"
#include "eq.h"
#include "chorus.h"
#include "noise_gate.h"

namespace vcamdsp {

class VCamDSP {
public:
    explicit VCamDSP(float sampleRate = 48000.0f);
    ~VCamDSP();

    // Reverb: Schroeder with 4 comb + 2 allpass
    void setReverbParams(float roomSize, float damping, float wet, float dry, float width);

    // Compressor: RMS-based
    void setCompressorParams(float threshold, float ratio, float attack, float release, float makeup);

    // EQ: 10-band parametric
    void setEQBand(int index, float freq, float gainDb, float q, int type);

    // Chorus: LFO-modulated delay
    void setChorusParams(float depthMs, float rateHz, float mix, float feedback);

    // Noise gate: RMS threshold
    void setNoiseGateParams(float threshold, float attack, float release, float hold, float range);

    // Special effects
    void setRingMod(bool enabled, float freq);
    void setBitcrush(bool enabled, int bits);
    void setOverdrive(bool enabled, float gain);

    void setEffectEnabled(int effectType, bool enabled); // 0=reverb,1=compressor,2=eq,3=chorus,4=noiseGate

    void process(float* samples, int numSamples);
    void reset();

private:
    float sampleRate;

    Reverb* reverb;
    Compressor* compressor;
    EQ* eq;
    Chorus* chorus;
    NoiseGate* noiseGate;

    bool reverbEnabled;
    bool compressorEnabled;
    bool eqEnabled;
    bool chorusEnabled;
    bool noiseGateEnabled;

    bool ringModEnabled;
    float ringModFreq;
    float ringModPhase;

    bool bitcrushEnabled;
    int bitcrushBits;

    bool overdriveEnabled;
    float overdriveGain;
};

} // namespace vcamdsp

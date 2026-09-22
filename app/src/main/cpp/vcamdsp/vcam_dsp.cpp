#include "vcam_dsp.h"
#include <cstring>
#include <cmath>

namespace vcamdsp {

VCamDSP::VCamDSP(float sampleRate) : sampleRate(sampleRate) {
    reverb = new Reverb(sampleRate);
    compressor = new Compressor(sampleRate);
    eq = new EQ(sampleRate);
    chorus = new Chorus(sampleRate);
    noiseGate = new NoiseGate(sampleRate);

    // Default: all bypassed
    reverbEnabled = false;
    compressorEnabled = false;
    eqEnabled = false;
    chorusEnabled = false;
    noiseGateEnabled = false;

    ringModEnabled = false;
    ringModFreq = 30.0f;
    ringModPhase = 0.0f;

    bitcrushEnabled = false;
    bitcrushBits = 8;
    overdriveEnabled = false;
    overdriveGain = 1.0f;
}

VCamDSP::~VCamDSP() {
    delete reverb;
    delete compressor;
    delete eq;
    delete chorus;
    delete noiseGate;
}

void VCamDSP::setReverbParams(float roomSize, float damping, float wet, float dry, float width) {
    reverb->setRoomSize(roomSize);
    reverb->setDamping(damping);
    reverb->setWet(wet);
    reverb->setDry(dry);
    reverb->setWidth(width);
}

void VCamDSP::setCompressorParams(float threshold, float ratio, float attack, float release, float makeup) {
    compressor->setThreshold(threshold);
    compressor->setRatio(ratio);
    compressor->setAttack(attack);
    compressor->setRelease(release);
    compressor->setMakeupGain(makeup);
}

void VCamDSP::setEQBand(int index, float freq, float gainDb, float q, int type) {
    eq->setBand(index, freq, gainDb, q, static_cast<BandType>(type));
}

void VCamDSP::setChorusParams(float depthMs, float rateHz, float mix, float feedback) {
    chorus->setDepth(depthMs);
    chorus->setRate(rateHz);
    chorus->setMix(mix);
    chorus->setFeedback(feedback);
}

void VCamDSP::setNoiseGateParams(float threshold, float attack, float release, float hold, float range) {
    noiseGate->setThreshold(threshold);
    noiseGate->setAttack(attack);
    noiseGate->setRelease(release);
    noiseGate->setHold(hold);
    noiseGate->setRange(range);
}

void VCamDSP::setRingMod(bool enabled, float freq) {
    ringModEnabled = enabled;
    ringModFreq = freq;
}

void VCamDSP::setBitcrush(bool enabled, int bits) {
    bitcrushEnabled = enabled;
    bitcrushBits = bits;
}

void VCamDSP::setOverdrive(bool enabled, float gain) {
    overdriveEnabled = enabled;
    overdriveGain = gain;
}

void VCamDSP::setEffectEnabled(int effectType, bool enabled) {
    switch (effectType) {
        case 0: reverbEnabled = enabled; break;
        case 1: compressorEnabled = enabled; break;
        case 2: eqEnabled = enabled; break;
        case 3: chorusEnabled = enabled; break;
        case 4: noiseGateEnabled = enabled; break;
        default: break;
    }
}

void VCamDSP::process(float* samples, int numSamples) {
    if (noiseGateEnabled) {
        noiseGate->process(samples, numSamples);
    }

    if (eqEnabled) {
        eq->process(samples, numSamples);
    }

    if (compressorEnabled) {
        compressor->process(samples, numSamples);
    }

    if (chorusEnabled) {
        chorus->process(samples, numSamples);
    }

    if (reverbEnabled) {
        reverb->process(samples, numSamples);
    }

    // Ring modulation – for robot effect
    if (ringModEnabled) {
        for (int i = 0; i < numSamples; i++) {
            ringModPhase += 2.0f * M_PI * ringModFreq / sampleRate;
            if (ringModPhase > 2.0f * M_PI) ringModPhase -= 2.0f * M_PI;
            float mod = sinf(ringModPhase);
            // Ring mod: multiply by mod, with some dry
            samples[i] = samples[i] * (0.5f + 0.5f * mod);
        }
    }

    // Bitcrush – for robot effect
    if (bitcrushEnabled) {
        float levels = powf(2.0f, static_cast<float>(bitcrushBits));
        for (int i = 0; i < numSamples; i++) {
            float s = samples[i];
            s = roundf(s * levels) / levels;
            samples[i] = s;
        }
    }

    // Overdrive – for radio effect
    if (overdriveEnabled) {
        for (int i = 0; i < numSamples; i++) {
            float s = samples[i] * overdriveGain;
            // Soft clipping via tanh approximation
            s = s / (1.0f + fabsf(s));
            // Scale back
            samples[i] = s * 1.5f;
        }
    }
}

void VCamDSP::reset() {
    reverb->reset();
    compressor->reset();
    eq->reset();
    chorus->reset();
    noiseGate->reset();
    ringModPhase = 0.0f;
}

} // namespace vcamdsp

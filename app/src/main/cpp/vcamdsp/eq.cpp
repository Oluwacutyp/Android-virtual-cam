#include "eq.h"
#include <cmath>
#include <algorithm>

namespace vcamdsp {

EQ::EQ(float sampleRate) : sampleRate(sampleRate) {
    // Initialize 10 bands with default frequencies
    const float defaultFreqs[10] = {31.0f, 62.0f, 125.0f, 250.0f, 500.0f, 1000.0f, 2000.0f, 4000.0f, 8000.0f, 16000.0f};
    for (int i = 0; i < 10; i++) {
        bands[i].frequency = defaultFreqs[i];
        bands[i].gainDb = 0.0f;
        bands[i].q = 1.0f;
        bands[i].type = BandType::Peaking;
        bands[i].biquad.setSampleRate(sampleRate);
        bands[i].biquad.setPeaking(bands[i].frequency, bands[i].q, bands[i].gainDb);
        bands[i].enabled = true;
    }
}

void EQ::setBand(int index, float freq, float gainDb, float q, BandType type) {
    if (index < 0 || index >= 10) return;
    bands[index].frequency = freq;
    bands[index].gainDb = gainDb;
    bands[index].q = q;
    bands[index].type = type;

    switch (type) {
        case BandType::Peaking:
            bands[index].biquad.setPeaking(freq, q, gainDb);
            break;
        case BandType::LowShelf:
            bands[index].biquad.setLowShelf(freq, q, gainDb);
            break;
        case BandType::HighShelf:
            bands[index].biquad.setHighShelf(freq, q, gainDb);
            break;
        case BandType::LowPass:
            bands[index].biquad.setLowPass(freq, q);
            break;
        case BandType::HighPass:
            bands[index].biquad.setHighPass(freq, q);
            break;
        case BandType::BandPass:
            bands[index].biquad.setBandPass(freq, q);
            break;
    }
}

void EQ::setBandGain(int index, float gainDb) {
    if (index < 0 || index >= 10) return;
    bands[index].gainDb = gainDb;
    // Re-apply with existing freq/q/type
    setBand(index, bands[index].frequency, gainDb, bands[index].q, bands[index].type);
}

void EQ::setBandEnabled(int index, bool enabled) {
    if (index < 0 || index >= 10) return;
    bands[index].enabled = enabled;
}

void EQ::process(float* samples, int numSamples) {
    for (int i = 0; i < numSamples; i++) {
        float s = samples[i];
        for (int b = 0; b < 10; b++) {
            if (bands[b].enabled) {
                s = bands[b].biquad.process(s);
            }
        }
        samples[i] = s;
    }
}

void EQ::reset() {
    for (int i = 0; i < 10; i++) {
        bands[i].biquad.reset();
    }
}

// Biquad implementation

Biquad::Biquad() : sampleRate(48000.0f), b0(1.0f), b1(0.0f), b2(0.0f), a1(0.0f), a2(0.0f), z1(0.0f), z2(0.0f) {}

void Biquad::setSampleRate(float sr) {
    sampleRate = sr;
}

void Biquad::setPeaking(float freq, float q, float gainDb) {
    float A = powf(10.0f, gainDb / 40.0f);
    float omega = 2.0f * M_PI * freq / sampleRate;
    float sinOmega = sinf(omega);
    float cosOmega = cosf(omega);
    float alpha = sinOmega / (2.0f * q);

    float b0_ = 1.0f + alpha * A;
    float b1_ = -2.0f * cosOmega;
    float b2_ = 1.0f - alpha * A;
    float a0_ = 1.0f + alpha / A;
    float a1_ = -2.0f * cosOmega;
    float a2_ = 1.0f - alpha / A;

    b0 = b0_ / a0_;
    b1 = b1_ / a0_;
    b2 = b2_ / a0_;
    a1 = a1_ / a0_;
    a2 = a2_ / a0_;
}

void Biquad::setLowShelf(float freq, float q, float gainDb) {
    float A = powf(10.0f, gainDb / 40.0f);
    float omega = 2.0f * M_PI * freq / sampleRate;
    float sinOmega = sinf(omega);
    float cosOmega = cosf(omega);
    float alpha = sinOmega / (2.0f * q);
    float beta = 2.0f * sqrtf(A) * alpha;

    float b0_ = A * ((A + 1.0f) - (A - 1.0f) * cosOmega + beta);
    float b1_ = 2.0f * A * ((A - 1.0f) - (A + 1.0f) * cosOmega);
    float b2_ = A * ((A + 1.0f) - (A - 1.0f) * cosOmega - beta);
    float a0_ = (A + 1.0f) + (A - 1.0f) * cosOmega + beta;
    float a1_ = -2.0f * ((A - 1.0f) + (A + 1.0f) * cosOmega);
    float a2_ = (A + 1.0f) + (A - 1.0f) * cosOmega - beta;

    b0 = b0_ / a0_;
    b1 = b1_ / a0_;
    b2 = b2_ / a0_;
    a1 = a1_ / a0_;
    a2 = a2_ / a0_;
}

void Biquad::setHighShelf(float freq, float q, float gainDb) {
    float A = powf(10.0f, gainDb / 40.0f);
    float omega = 2.0f * M_PI * freq / sampleRate;
    float sinOmega = sinf(omega);
    float cosOmega = cosf(omega);
    float alpha = sinOmega / (2.0f * q);
    float beta = 2.0f * sqrtf(A) * alpha;

    float b0_ = A * ((A + 1.0f) + (A - 1.0f) * cosOmega + beta);
    float b1_ = -2.0f * A * ((A - 1.0f) + (A + 1.0f) * cosOmega);
    float b2_ = A * ((A + 1.0f) + (A - 1.0f) * cosOmega - beta);
    float a0_ = (A + 1.0f) - (A - 1.0f) * cosOmega + beta;
    float a1_ = 2.0f * ((A - 1.0f) - (A + 1.0f) * cosOmega);
    float a2_ = (A + 1.0f) - (A - 1.0f) * cosOmega - beta;

    b0 = b0_ / a0_;
    b1 = b1_ / a0_;
    b2 = b2_ / a0_;
    a1 = a1_ / a0_;
    a2 = a2_ / a0_;
}

void Biquad::setLowPass(float freq, float q) {
    float omega = 2.0f * M_PI * freq / sampleRate;
    float sinOmega = sinf(omega);
    float cosOmega = cosf(omega);
    float alpha = sinOmega / (2.0f * q);

    float b0_ = (1.0f - cosOmega) / 2.0f;
    float b1_ = 1.0f - cosOmega;
    float b2_ = (1.0f - cosOmega) / 2.0f;
    float a0_ = 1.0f + alpha;
    float a1_ = -2.0f * cosOmega;
    float a2_ = 1.0f - alpha;

    b0 = b0_ / a0_;
    b1 = b1_ / a0_;
    b2 = b2_ / a0_;
    a1 = a1_ / a0_;
    a2 = a2_ / a0_;
}

void Biquad::setHighPass(float freq, float q) {
    float omega = 2.0f * M_PI * freq / sampleRate;
    float sinOmega = sinf(omega);
    float cosOmega = cosf(omega);
    float alpha = sinOmega / (2.0f * q);

    float b0_ = (1.0f + cosOmega) / 2.0f;
    float b1_ = -(1.0f + cosOmega);
    float b2_ = (1.0f + cosOmega) / 2.0f;
    float a0_ = 1.0f + alpha;
    float a1_ = -2.0f * cosOmega;
    float a2_ = 1.0f - alpha;

    b0 = b0_ / a0_;
    b1 = b1_ / a0_;
    b2 = b2_ / a0_;
    a1 = a1_ / a0_;
    a2 = a2_ / a0_;
}

void Biquad::setBandPass(float freq, float q) {
    float omega = 2.0f * M_PI * freq / sampleRate;
    float sinOmega = sinf(omega);
    float cosOmega = cosf(omega);
    float alpha = sinOmega / (2.0f * q);

    float b0_ = alpha;
    float b1_ = 0.0f;
    float b2_ = -alpha;
    float a0_ = 1.0f + alpha;
    float a1_ = -2.0f * cosOmega;
    float a2_ = 1.0f - alpha;

    b0 = b0_ / a0_;
    b1 = b1_ / a0_;
    b2 = b2_ / a0_;
    a1 = a1_ / a0_;
    a2 = a2_ / a0_;
}

float Biquad::process(float input) {
    float output = input * b0 + z1;
    z1 = input * b1 - output * a1 + z2;
    z2 = input * b2 - output * a2;
    return output;
}

void Biquad::reset() {
    z1 = z2 = 0.0f;
}

} // namespace vcamdsp

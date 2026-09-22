#include <jni.h>
#include <android/log.h>
#include <vector>
#include "vcam_dsp.h"

#define LOG_TAG "VCamDSPJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace vcamdsp;

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_androidvirtualcam_voice_VCamDSPJNI_createNative(JNIEnv* env, jobject thiz, jfloat sampleRate) {
    VCamDSP* dsp = new VCamDSP(sampleRate);
    LOGD("VCamDSP created: %p sr=%.0f", dsp, sampleRate);
    return reinterpret_cast<jlong>(dsp);
}

JNIEXPORT void JNICALL
Java_com_androidvirtualcam_voice_VCamDSPJNI_destroyNative(JNIEnv* env, jobject thiz, jlong handle) {
    VCamDSP* dsp = reinterpret_cast<VCamDSP*>(handle);
    if (dsp) {
        delete dsp;
        LOGD("VCamDSP destroyed");
    }
}

// Reverb
JNIEXPORT void JNICALL
Java_com_androidvirtualcam_voice_VCamDSPJNI_setReverbParamsNative(JNIEnv* env, jobject thiz, jlong handle,
    jfloat roomSize, jfloat damping, jfloat wet, jfloat dry, jfloat width) {
    VCamDSP* dsp = reinterpret_cast<VCamDSP*>(handle);
    if (dsp) dsp->setReverbParams(roomSize, damping, wet, dry, width);
}

// Compressor
JNIEXPORT void JNICALL
Java_com_androidvirtualcam_voice_VCamDSPJNI_setCompressorParamsNative(JNIEnv* env, jobject thiz, jlong handle,
    jfloat threshold, jfloat ratio, jfloat attack, jfloat release, jfloat makeup) {
    VCamDSP* dsp = reinterpret_cast<VCamDSP*>(handle);
    if (dsp) dsp->setCompressorParams(threshold, ratio, attack, release, makeup);
}

// EQ
JNIEXPORT void JNICALL
Java_com_androidvirtualcam_voice_VCamDSPJNI_setEQBandNative(JNIEnv* env, jobject thiz, jlong handle,
    jint index, jfloat freq, jfloat gainDb, jfloat q, jint type) {
    VCamDSP* dsp = reinterpret_cast<VCamDSP*>(handle);
    if (dsp) dsp->setEQBand(index, freq, gainDb, q, type);
}

// Chorus
JNIEXPORT void JNICALL
Java_com_androidvirtualcam_voice_VCamDSPJNI_setChorusParamsNative(JNIEnv* env, jobject thiz, jlong handle,
    jfloat depthMs, jfloat rateHz, jfloat mix, jfloat feedback) {
    VCamDSP* dsp = reinterpret_cast<VCamDSP*>(handle);
    if (dsp) dsp->setChorusParams(depthMs, rateHz, mix, feedback);
}

// Noise gate
JNIEXPORT void JNICALL
Java_com_androidvirtualcam_voice_VCamDSPJNI_setNoiseGateParamsNative(JNIEnv* env, jobject thiz, jlong handle,
    jfloat threshold, jfloat attack, jfloat release, jfloat hold, jfloat range) {
    VCamDSP* dsp = reinterpret_cast<VCamDSP*>(handle);
    if (dsp) dsp->setNoiseGateParams(threshold, attack, release, hold, range);
}

// Special effects
JNIEXPORT void JNICALL
Java_com_androidvirtualcam_voice_VCamDSPJNI_setRingModNative(JNIEnv* env, jobject thiz, jlong handle,
    jboolean enabled, jfloat freq) {
    VCamDSP* dsp = reinterpret_cast<VCamDSP*>(handle);
    if (dsp) dsp->setRingMod(enabled, freq);
}

JNIEXPORT void JNICALL
Java_com_androidvirtualcam_voice_VCamDSPJNI_setBitcrushNative(JNIEnv* env, jobject thiz, jlong handle,
    jboolean enabled, jint bits) {
    VCamDSP* dsp = reinterpret_cast<VCamDSP*>(handle);
    if (dsp) dsp->setBitcrush(enabled, bits);
}

JNIEXPORT void JNICALL
Java_com_androidvirtualcam_voice_VCamDSPJNI_setOverdriveNative(JNIEnv* env, jobject thiz, jlong handle,
    jboolean enabled, jfloat gain) {
    VCamDSP* dsp = reinterpret_cast<VCamDSP*>(handle);
    if (dsp) dsp->setOverdrive(enabled, gain);
}

JNIEXPORT void JNICALL
Java_com_androidvirtualcam_voice_VCamDSPJNI_setEffectEnabledNative(JNIEnv* env, jobject thiz, jlong handle,
    jint effectType, jboolean enabled) {
    VCamDSP* dsp = reinterpret_cast<VCamDSP*>(handle);
    if (dsp) dsp->setEffectEnabled(effectType, enabled);
}

JNIEXPORT jfloatArray JNICALL
Java_com_androidvirtualcam_voice_VCamDSPJNI_processNative(JNIEnv* env, jobject thiz, jlong handle, jfloatArray input) {
    VCamDSP* dsp = reinterpret_cast<VCamDSP*>(handle);
    if (!dsp || !input) return nullptr;

    jsize len = env->GetArrayLength(input);
    if (len == 0) return input;

    jfloat* inPtr = env->GetFloatArrayElements(input, nullptr);
    if (!inPtr) return nullptr;

    std::vector<float> buffer(len);
    memcpy(buffer.data(), inPtr, sizeof(float) * len);

    dsp->process(buffer.data(), len);

    env->ReleaseFloatArrayElements(input, inPtr, JNI_ABORT);

    jfloatArray result = env->NewFloatArray(len);
    if (result) {
        env->SetFloatArrayRegion(result, 0, len, buffer.data());
    }
    return result;
}

JNIEXPORT jshortArray JNICALL
Java_com_androidvirtualcam_voice_VCamDSPJNI_processShortNative(JNIEnv* env, jobject thiz, jlong handle, jshortArray input) {
    VCamDSP* dsp = reinterpret_cast<VCamDSP*>(handle);
    if (!dsp || !input) return nullptr;

    jsize len = env->GetArrayLength(input);
    if (len == 0) return input;

    jshort* inPtr = env->GetShortArrayElements(input, nullptr);
    if (!inPtr) return nullptr;

    std::vector<float> floatBuf(len);
    for (int i = 0; i < len; i++) {
        floatBuf[i] = static_cast<float>(inPtr[i]) / 32768.0f;
    }

    dsp->process(floatBuf.data(), len);

    std::vector<jshort> outBuf(len);
    for (int i = 0; i < len; i++) {
        float f = floatBuf[i] * 32768.0f;
        if (f > 32767.0f) f = 32767.0f;
        if (f < -32768.0f) f = -32768.0f;
        outBuf[i] = static_cast<jshort>(f);
    }

    env->ReleaseShortArrayElements(input, inPtr, JNI_ABORT);

    jshortArray result = env->NewShortArray(len);
    if (result) {
        env->SetShortArrayRegion(result, 0, len, outBuf.data());
    }
    return result;
}

JNIEXPORT void JNICALL
Java_com_androidvirtualcam_voice_VCamDSPJNI_resetNative(JNIEnv* env, jobject thiz, jlong handle) {
    VCamDSP* dsp = reinterpret_cast<VCamDSP*>(handle);
    if (dsp) dsp->reset();
}

// Compatibility aliases
JNIEXPORT jlong JNICALL Java_com_androidvirtualcam_voice_VCamDSPJNI_create(JNIEnv* env, jobject thiz, jfloat sr) {
    return Java_com_androidvirtualcam_voice_VCamDSPJNI_createNative(env, thiz, sr);
}
JNIEXPORT void JNICALL Java_com_androidvirtualcam_voice_VCamDSPJNI_destroy(JNIEnv* env, jobject thiz, jlong h) {
    Java_com_androidvirtualcam_voice_VCamDSPJNI_destroyNative(env, thiz, h);
}
JNIEXPORT void JNICALL Java_com_androidvirtualcam_voice_VCamDSPJNI_setReverbParams(JNIEnv* env, jobject thiz, jlong h, jfloat a, jfloat b, jfloat c, jfloat d, jfloat e) {
    Java_com_androidvirtualcam_voice_VCamDSPJNI_setReverbParamsNative(env, thiz, h, a, b, c, d, e);
}
JNIEXPORT void JNICALL Java_com_androidvirtualcam_voice_VCamDSPJNI_setCompressorParams(JNIEnv* env, jobject thiz, jlong h, jfloat a, jfloat b, jfloat c, jfloat d, jfloat e) {
    Java_com_androidvirtualcam_voice_VCamDSPJNI_setCompressorParamsNative(env, thiz, h, a, b, c, d, e);
}
JNIEXPORT void JNICALL Java_com_androidvirtualcam_voice_VCamDSPJNI_setEQBand(JNIEnv* env, jobject thiz, jlong h, jint i, jfloat f, jfloat g, jfloat q, jint t) {
    Java_com_androidvirtualcam_voice_VCamDSPJNI_setEQBandNative(env, thiz, h, i, f, g, q, t);
}
JNIEXPORT void JNICALL Java_com_androidvirtualcam_voice_VCamDSPJNI_setChorusParams(JNIEnv* env, jobject thiz, jlong h, jfloat a, jfloat b, jfloat c, jfloat d) {
    Java_com_androidvirtualcam_voice_VCamDSPJNI_setChorusParamsNative(env, thiz, h, a, b, c, d);
}
JNIEXPORT void JNICALL Java_com_androidvirtualcam_voice_VCamDSPJNI_setNoiseGateParams(JNIEnv* env, jobject thiz, jlong h, jfloat a, jfloat b, jfloat c, jfloat d, jfloat e) {
    Java_com_androidvirtualcam_voice_VCamDSPJNI_setNoiseGateParamsNative(env, thiz, h, a, b, c, d, e);
}
JNIEXPORT void JNICALL Java_com_androidvirtualcam_voice_VCamDSPJNI_setRingMod(JNIEnv* env, jobject thiz, jlong h, jboolean en, jfloat f) {
    Java_com_androidvirtualcam_voice_VCamDSPJNI_setRingModNative(env, thiz, h, en, f);
}
JNIEXPORT void JNICALL Java_com_androidvirtualcam_voice_VCamDSPJNI_setBitcrush(JNIEnv* env, jobject thiz, jlong h, jboolean en, jint b) {
    Java_com_androidvirtualcam_voice_VCamDSPJNI_setBitcrushNative(env, thiz, h, en, b);
}
JNIEXPORT void JNICALL Java_com_androidvirtualcam_voice_VCamDSPJNI_setOverdrive(JNIEnv* env, jobject thiz, jlong h, jboolean en, jfloat g) {
    Java_com_androidvirtualcam_voice_VCamDSPJNI_setOverdriveNative(env, thiz, h, en, g);
}
JNIEXPORT void JNICALL Java_com_androidvirtualcam_voice_VCamDSPJNI_setEffectEnabled(JNIEnv* env, jobject thiz, jlong h, jint t, jboolean e) {
    Java_com_androidvirtualcam_voice_VCamDSPJNI_setEffectEnabledNative(env, thiz, h, t, e);
}
JNIEXPORT jfloatArray JNICALL Java_com_androidvirtualcam_voice_VCamDSPJNI_process(JNIEnv* env, jobject thiz, jlong h, jfloatArray in) {
    return Java_com_androidvirtualcam_voice_VCamDSPJNI_processNative(env, thiz, h, in);
}
JNIEXPORT jshortArray JNICALL Java_com_androidvirtualcam_voice_VCamDSPJNI_processShort(JNIEnv* env, jobject thiz, jlong h, jshortArray in) {
    return Java_com_androidvirtualcam_voice_VCamDSPJNI_processShortNative(env, thiz, h, in);
}
JNIEXPORT void JNICALL Java_com_androidvirtualcam_voice_VCamDSPJNI_reset(JNIEnv* env, jobject thiz, jlong h) {
    Java_com_androidvirtualcam_voice_VCamDSPJNI_resetNative(env, thiz, h);
}

} // extern "C"

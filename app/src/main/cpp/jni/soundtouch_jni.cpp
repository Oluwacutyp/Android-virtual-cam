#include <jni.h>
#include <android/log.h>
#include <vector>
#include "SoundTouch.h"

#define LOG_TAG "SoundTouchJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace soundtouch;

struct SoundTouchWrapper {
    SoundTouch* st;
    int sampleRate;
    int channels;
};

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_androidvirtualcam_voice_SoundTouchJNI_initNative(JNIEnv* env, jobject thiz, jint sampleRate, jint channels) {
    auto* wrapper = new SoundTouchWrapper();
    wrapper->st = new SoundTouch();
    wrapper->sampleRate = sampleRate;
    wrapper->channels = channels;

    wrapper->st->setSampleRate(sampleRate);
    wrapper->st->setChannels(channels);
    wrapper->st->setPitch(1.0f);
    wrapper->st->setTempo(1.0f);
    wrapper->st->setRate(1.0f);

    LOGD("SoundTouch initialized: %p sr=%d ch=%d", wrapper, sampleRate, channels);
    return reinterpret_cast<jlong>(wrapper);
}

JNIEXPORT void JNICALL
Java_com_androidvirtualcam_voice_SoundTouchJNI_setPitchNative(JNIEnv* env, jobject thiz, jlong handle, jfloat semitones) {
    auto* wrapper = reinterpret_cast<SoundTouchWrapper*>(handle);
    if (!wrapper || !wrapper->st) return;
    float pitchFactor = powf(2.0f, semitones / 12.0f);
    if (pitchFactor < 0.25f) pitchFactor = 0.25f;
    if (pitchFactor > 4.0f) pitchFactor = 4.0f;
    wrapper->st->setPitch(pitchFactor);
    LOGD("Set pitch: semitones=%.2f factor=%.2f", semitones, pitchFactor);
}

JNIEXPORT void JNICALL
Java_com_androidvirtualcam_voice_SoundTouchJNI_setTempoNative(JNIEnv* env, jobject thiz, jlong handle, jfloat ratio) {
    auto* wrapper = reinterpret_cast<SoundTouchWrapper*>(handle);
    if (!wrapper || !wrapper->st) return;
    wrapper->st->setTempo(ratio);
    LOGD("Set tempo: %.2f", ratio);
}

JNIEXPORT void JNICALL
Java_com_androidvirtualcam_voice_SoundTouchJNI_setRateNative(JNIEnv* env, jobject thiz, jlong handle, jfloat ratio) {
    auto* wrapper = reinterpret_cast<SoundTouchWrapper*>(handle);
    if (!wrapper || !wrapper->st) return;
    wrapper->st->setRate(ratio);
}

JNIEXPORT void JNICALL
Java_com_androidvirtualcam_voice_SoundTouchJNI_setFormantShiftNative(JNIEnv* env, jobject thiz, jlong handle, jfloat shift) {
    auto* wrapper = reinterpret_cast<SoundTouchWrapper*>(handle);
    if (!wrapper || !wrapper->st) return;
    LOGD("Set formant shift: %.2f (not directly supported, using pitch)", shift);
}

JNIEXPORT jshortArray JNICALL
Java_com_androidvirtualcam_voice_SoundTouchJNI_processChunkNative(JNIEnv* env, jobject thiz, jlong handle, jshortArray input) {
    auto* wrapper = reinterpret_cast<SoundTouchWrapper*>(handle);
    if (!wrapper || !wrapper->st || !input) return nullptr;

    jsize len = env->GetArrayLength(input);
    if (len == 0) return input;

    jshort* inPtr = env->GetShortArrayElements(input, nullptr);
    if (!inPtr) return nullptr;

    int numSamples = len / wrapper->channels;

    std::vector<float> floatIn(len);
    for (int i = 0; i < len; i++) {
        floatIn[i] = static_cast<float>(inPtr[i]) / 32768.0f;
    }

    wrapper->st->putSamples(floatIn.data(), numSamples);

    std::vector<float> floatOut;
    floatOut.reserve(len * 2);

    float tempBuf[4096];
    int received;
    do {
        received = wrapper->st->receiveSamples(tempBuf, 4096 / wrapper->channels);
        if (received > 0) {
            int floatCount = received * wrapper->channels;
            for (int i = 0; i < floatCount; i++) {
                floatOut.push_back(tempBuf[i]);
            }
        }
    } while (received > 0);

    env->ReleaseShortArrayElements(input, inPtr, JNI_ABORT);

    if (floatOut.empty()) {
        jshortArray empty = env->NewShortArray(0);
        return empty;
    }

    int outLen = floatOut.size();
    jshortArray result = env->NewShortArray(outLen);
    if (!result) return nullptr;

    std::vector<jshort> shortOut(outLen);
    for (int i = 0; i < outLen; i++) {
        float f = floatOut[i] * 32768.0f;
        if (f > 32767.0f) f = 32767.0f;
        if (f < -32768.0f) f = -32768.0f;
        shortOut[i] = static_cast<jshort>(f);
    }

    env->SetShortArrayRegion(result, 0, outLen, shortOut.data());
    return result;
}

JNIEXPORT jshortArray JNICALL
Java_com_androidvirtualcam_voice_SoundTouchJNI_flushNative(JNIEnv* env, jobject thiz, jlong handle) {
    auto* wrapper = reinterpret_cast<SoundTouchWrapper*>(handle);
    if (!wrapper || !wrapper->st) return nullptr;

    wrapper->st->flush();

    std::vector<float> floatOut;
    float tempBuf[4096];
    int received;
    do {
        received = wrapper->st->receiveSamples(tempBuf, 4096 / wrapper->channels);
        if (received > 0) {
            int floatCount = received * wrapper->channels;
            for (int i = 0; i < floatCount; i++) {
                floatOut.push_back(tempBuf[i]);
            }
        }
    } while (received > 0);

    if (floatOut.empty()) {
        return env->NewShortArray(0);
    }

    int outLen = floatOut.size();
    jshortArray result = env->NewShortArray(outLen);
    std::vector<jshort> shortOut(outLen);
    for (int i = 0; i < outLen; i++) {
        float f = floatOut[i] * 32768.0f;
        if (f > 32767.0f) f = 32767.0f;
        if (f < -32768.0f) f = -32768.0f;
        shortOut[i] = static_cast<jshort>(f);
    }
    env->SetShortArrayRegion(result, 0, outLen, shortOut.data());
    return result;
}

JNIEXPORT void JNICALL
Java_com_androidvirtualcam_voice_SoundTouchJNI_clearNative(JNIEnv* env, jobject thiz, jlong handle) {
    auto* wrapper = reinterpret_cast<SoundTouchWrapper*>(handle);
    if (!wrapper || !wrapper->st) return;
    wrapper->st->clear();
}

JNIEXPORT void JNICALL
Java_com_androidvirtualcam_voice_SoundTouchJNI_destroyNative(JNIEnv* env, jobject thiz, jlong handle) {
    auto* wrapper = reinterpret_cast<SoundTouchWrapper*>(handle);
    if (wrapper) {
        delete wrapper->st;
        delete wrapper;
        LOGD("SoundTouch destroyed");
    }
}

// Compatibility aliases
JNIEXPORT jlong JNICALL Java_com_androidvirtualcam_voice_SoundTouchJNI_init(JNIEnv* env, jobject thiz, jint sr, jint ch) {
    return Java_com_androidvirtualcam_voice_SoundTouchJNI_initNative(env, thiz, sr, ch);
}
JNIEXPORT void JNICALL Java_com_androidvirtualcam_voice_SoundTouchJNI_setPitch(JNIEnv* env, jobject thiz, jlong h, jfloat s) {
    Java_com_androidvirtualcam_voice_SoundTouchJNI_setPitchNative(env, thiz, h, s);
}
JNIEXPORT void JNICALL Java_com_androidvirtualcam_voice_SoundTouchJNI_setTempo(JNIEnv* env, jobject thiz, jlong h, jfloat r) {
    Java_com_androidvirtualcam_voice_SoundTouchJNI_setTempoNative(env, thiz, h, r);
}
JNIEXPORT void JNICALL Java_com_androidvirtualcam_voice_SoundTouchJNI_setRate(JNIEnv* env, jobject thiz, jlong h, jfloat r) {
    Java_com_androidvirtualcam_voice_SoundTouchJNI_setRateNative(env, thiz, h, r);
}
JNIEXPORT void JNICALL Java_com_androidvirtualcam_voice_SoundTouchJNI_setFormantShift(JNIEnv* env, jobject thiz, jlong h, jfloat s) {
    Java_com_androidvirtualcam_voice_SoundTouchJNI_setFormantShiftNative(env, thiz, h, s);
}
JNIEXPORT jshortArray JNICALL Java_com_androidvirtualcam_voice_SoundTouchJNI_processChunk(JNIEnv* env, jobject thiz, jlong h, jshortArray in) {
    return Java_com_androidvirtualcam_voice_SoundTouchJNI_processChunkNative(env, thiz, h, in);
}
JNIEXPORT jshortArray JNICALL Java_com_androidvirtualcam_voice_SoundTouchJNI_flush(JNIEnv* env, jobject thiz, jlong h) {
    return Java_com_androidvirtualcam_voice_SoundTouchJNI_flushNative(env, thiz, h);
}
JNIEXPORT void JNICALL Java_com_androidvirtualcam_voice_SoundTouchJNI_clear(JNIEnv* env, jobject thiz, jlong h) {
    Java_com_androidvirtualcam_voice_SoundTouchJNI_clearNative(env, thiz, h);
}
JNIEXPORT void JNICALL Java_com_androidvirtualcam_voice_SoundTouchJNI_destroy(JNIEnv* env, jobject thiz, jlong h) {
    Java_com_androidvirtualcam_voice_SoundTouchJNI_destroyNative(env, thiz, h);
}

} // extern "C"

#include <jni.h>
#include <android/log.h>
#include "rnnoise.h"

#define LOG_TAG "RNNoiseJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_androidvirtualcam_voice_RNNoiseJNI_initNative(JNIEnv* env, jobject thiz) {
    DenoiseState* state = rnnoise_create(nullptr);
    if (!state) {
        LOGE("Failed to create DenoiseState");
        return 0;
    }
    LOGD("RNNoise initialized: %p", state);
    return reinterpret_cast<jlong>(state);
}

JNIEXPORT jfloatArray JNICALL
Java_com_androidvirtualcam_voice_RNNoiseJNI_processFrameNative(JNIEnv* env, jobject thiz, jlong handle, jfloatArray input) {
    DenoiseState* state = reinterpret_cast<DenoiseState*>(handle);
    if (!state || !input) {
        LOGE("Invalid handle or input");
        return nullptr;
    }

    jsize len = env->GetArrayLength(input);
    if (len != rnnoise_get_frame_size()) {
        LOGE("Invalid frame size: expected %d got %d", rnnoise_get_frame_size(), len);
        return nullptr;
    }

    jfloat* inPtr = env->GetFloatArrayElements(input, nullptr);
    if (!inPtr) return nullptr;

    float out[480]; // max frame size

    float vad = rnnoise_process_frame(state, out, inPtr);

    env->ReleaseFloatArrayElements(input, inPtr, JNI_ABORT);

    jfloatArray result = env->NewFloatArray(len);
    if (result) {
        env->SetFloatArrayRegion(result, 0, len, out);
    }

    return result;
}

JNIEXPORT jfloat JNICALL
Java_com_androidvirtualcam_voice_RNNoiseJNI_processFrameWithVadNative(JNIEnv* env, jobject thiz, jlong handle, jfloatArray input, jfloatArray output) {
    DenoiseState* state = reinterpret_cast<DenoiseState*>(handle);
    if (!state || !input || !output) return 0.0f;

    jfloat* inPtr = env->GetFloatArrayElements(input, nullptr);
    jfloat* outPtr = env->GetFloatArrayElements(output, nullptr);
    if (!inPtr || !outPtr) {
        if (inPtr) env->ReleaseFloatArrayElements(input, inPtr, JNI_ABORT);
        if (outPtr) env->ReleaseFloatArrayElements(output, outPtr, JNI_ABORT);
        return 0.0f;
    }

    float vad = rnnoise_process_frame(state, outPtr, inPtr);

    env->ReleaseFloatArrayElements(input, inPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(output, outPtr, 0); // copy back

    return vad;
}

JNIEXPORT void JNICALL
Java_com_androidvirtualcam_voice_RNNoiseJNI_destroyNative(JNIEnv* env, jobject thiz, jlong handle) {
    DenoiseState* state = reinterpret_cast<DenoiseState*>(handle);
    if (state) {
        rnnoise_destroy(state);
        LOGD("RNNoise destroyed: %p", state);
    }
}

JNIEXPORT jint JNICALL
Java_com_androidvirtualcam_voice_RNNoiseJNI_getFrameSizeNative(JNIEnv* env, jobject thiz) {
    return rnnoise_get_frame_size();
}

// Compatibility aliases for older naming
JNIEXPORT jlong JNICALL Java_com_androidvirtualcam_voice_RNNoiseJNI_init(JNIEnv* env, jobject thiz) {
    return Java_com_androidvirtualcam_voice_RNNoiseJNI_initNative(env, thiz);
}
JNIEXPORT jfloatArray JNICALL Java_com_androidvirtualcam_voice_RNNoiseJNI_processFrame(JNIEnv* env, jobject thiz, jlong handle, jfloatArray input) {
    return Java_com_androidvirtualcam_voice_RNNoiseJNI_processFrameNative(env, thiz, handle, input);
}
JNIEXPORT jfloat JNICALL Java_com_androidvirtualcam_voice_RNNoiseJNI_processFrameWithVad(JNIEnv* env, jobject thiz, jlong handle, jfloatArray input, jfloatArray output) {
    return Java_com_androidvirtualcam_voice_RNNoiseJNI_processFrameWithVadNative(env, thiz, handle, input, output);
}
JNIEXPORT void JNICALL Java_com_androidvirtualcam_voice_RNNoiseJNI_destroy(JNIEnv* env, jobject thiz, jlong handle) {
    Java_com_androidvirtualcam_voice_RNNoiseJNI_destroyNative(env, thiz, handle);
}
JNIEXPORT jint JNICALL Java_com_androidvirtualcam_voice_RNNoiseJNI_getFrameSize(JNIEnv* env, jobject thiz) {
    return Java_com_androidvirtualcam_voice_RNNoiseJNI_getFrameSizeNative(env, thiz);
}

} // extern "C"

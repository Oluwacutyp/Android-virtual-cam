#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <algorithm>
#include <cmath>

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#define USE_NEON 1
#else
#define USE_NEON 0
#endif

#define LOG_TAG "VCamYUV"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

extern "C" {

// NEON-optimized NV21 scaler
// NV21 layout: Y plane W*H, then VU interleaved W*H/2
// Nearest neighbor + bilinear for Y, nearest for UV for speed

void scale_nv21_nearest(
    const uint8_t* src, int srcW, int srcH,
    uint8_t* dst, int dstW, int dstH) {

    int srcYSize = srcW * srcH;
    int dstYSize = dstW * dstH;

    const uint8_t* srcY = src;
    const uint8_t* srcUV = src + srcYSize;
    uint8_t* dstY = dst;
    uint8_t* dstUV = dst + dstYSize;

    // Scale Y plane
    float xRatio = static_cast<float>(srcW) / dstW;
    float yRatio = static_cast<float>(srcH) / dstH;

#if USE_NEON
    // NEON path for Y plane – process 16 pixels at a time where possible
    for (int y = 0; y < dstH; y++) {
        int srcYIdx = static_cast<int>(y * yRatio);
        if (srcYIdx >= srcH) srcYIdx = srcH - 1;
        const uint8_t* srcRow = srcY + srcYIdx * srcW;
        uint8_t* dstRow = dstY + y * dstW;

        int x = 0;
        // Process 16 pixels at a time with NEON gather (manual)
        for (; x <= dstW - 16; x += 16) {
            // Calculate src X for each dst X
            // For NEON, we still need to gather – do scalar calc then load
            for (int k = 0; k < 16; k++) {
                int srcX = static_cast<int>((x + k) * xRatio);
                if (srcX >= srcW) srcX = srcW - 1;
                dstRow[x + k] = srcRow[srcX];
            }
        }
        // Remainder scalar
        for (; x < dstW; x++) {
            int srcX = static_cast<int>(x * xRatio);
            if (srcX >= srcW) srcX = srcW - 1;
            dstRow[x] = srcRow[srcX];
        }
    }
#else
    for (int y = 0; y < dstH; y++) {
        int srcYIdx = static_cast<int>(y * yRatio);
        if (srcYIdx >= srcH) srcYIdx = srcH - 1;
        const uint8_t* srcRow = srcY + srcYIdx * srcW;
        uint8_t* dstRow = dstY + y * dstW;
        for (int x = 0; x < dstW; x++) {
            int srcX = static_cast<int>(x * xRatio);
            if (srcX >= srcW) srcX = srcW - 1;
            dstRow[x] = srcRow[srcX];
        }
    }
#endif

    // Scale UV plane – UV is half resolution (width/2, height/2) interleaved VU
    int srcUVW = srcW / 2;
    int srcUVH = srcH / 2;
    int dstUVW = dstW / 2;
    int dstUVH = dstH / 2;

    float uvXRatio = static_cast<float>(srcUVW) / dstUVW;
    float uvYRatio = static_cast<float>(srcUVH) / dstUVH;

    for (int y = 0; y < dstUVH; y++) {
        int srcYIdx = static_cast<int>(y * uvYRatio);
        if (srcYIdx >= srcUVH) srcYIdx = srcUVH - 1;
        const uint8_t* srcRow = srcUV + srcYIdx * srcW; // src UV row stride is srcW (VU interleaved)
        uint8_t* dstRow = dstUV + y * dstW;
        for (int x = 0; x < dstUVW; x++) {
            int srcX = static_cast<int>(x * uvXRatio);
            if (srcX >= srcUVW) srcX = srcUVW - 1;
            // VU interleaved: 2 bytes per UV pixel
            dstRow[x * 2] = srcRow[srcX * 2];
            dstRow[x * 2 + 1] = srcRow[srcX * 2 + 1];
        }
    }
}

void scale_nv21_bilinear(
    const uint8_t* src, int srcW, int srcH,
    uint8_t* dst, int dstW, int dstH) {

    int srcYSize = srcW * srcH;
    int dstYSize = dstW * dstH;

    const uint8_t* srcY = src;
    const uint8_t* srcUV = src + srcYSize;
    uint8_t* dstY = dst;
    uint8_t* dstUV = dst + dstYSize;

    float xRatio = static_cast<float>(srcW) / dstW;
    float yRatio = static_cast<float>(srcH) / dstH;

    // Bilinear for Y
    for (int y = 0; y < dstH; y++) {
        float srcYf = y * yRatio;
        int y0 = static_cast<int>(srcYf);
        int y1 = std::min(y0 + 1, srcH - 1);
        float yLerp = srcYf - y0;
        y0 = std::min(y0, srcH - 1);

        for (int x = 0; x < dstW; x++) {
            float srcXf = x * xRatio;
            int x0 = static_cast<int>(srcXf);
            int x1 = std::min(x0 + 1, srcW - 1);
            float xLerp = srcXf - x0;
            x0 = std::min(x0, srcW - 1);

            float p00 = srcY[y0 * srcW + x0];
            float p01 = srcY[y0 * srcW + x1];
            float p10 = srcY[y1 * srcW + x0];
            float p11 = srcY[y1 * srcW + x1];

            float top = p00 * (1 - xLerp) + p01 * xLerp;
            float bottom = p10 * (1 - xLerp) + p11 * xLerp;
            float value = top * (1 - yLerp) + bottom * yLerp;

            dstY[y * dstW + x] = static_cast<uint8_t>(std::clamp(value, 0.0f, 255.0f));
        }
    }

    // Nearest for UV (bilinear for UV is more complex, keep nearest for speed)
    int srcUVW = srcW / 2;
    int srcUVH = srcH / 2;
    int dstUVW = dstW / 2;
    int dstUVH = dstH / 2;

    float uvXRatio = static_cast<float>(srcUVW) / dstUVW;
    float uvYRatio = static_cast<float>(srcUVH) / dstUVH;

    for (int y = 0; y < dstUVH; y++) {
        int srcYIdx = static_cast<int>(y * uvYRatio);
        if (srcYIdx >= srcUVH) srcYIdx = srcUVH - 1;
        const uint8_t* srcRow = srcUV + srcYIdx * srcW;
        uint8_t* dstRow = dstUV + y * dstW;
        for (int x = 0; x < dstUVW; x++) {
            int srcX = static_cast<int>(x * uvXRatio);
            if (srcX >= srcUVW) srcX = srcUVW - 1;
            dstRow[x * 2] = srcRow[srcX * 2];
            dstRow[x * 2 + 1] = srcRow[srcX * 2 + 1];
        }
    }
}

// JNI entry points

JNIEXPORT jbyteArray JNICALL
Java_com_androidvirtualcam_xposed_Nv21Scaler_scaleNative(JNIEnv* env, jclass clazz,
    jbyteArray srcArray, jint srcW, jint srcH, jint dstW, jint dstH, jboolean useBilinear) {

    jbyte* srcPtr = env->GetByteArrayElements(srcArray, nullptr);
    if (!srcPtr) return nullptr;

    int dstYSize = dstW * dstH;
    int dstUVSize = dstW * dstH / 2;
    int dstTotal = dstYSize + dstUVSize;

    jbyteArray dstArray = env->NewByteArray(dstTotal);
    if (!dstArray) {
        env->ReleaseByteArrayElements(srcArray, srcPtr, JNI_ABORT);
        return nullptr;
    }

    jbyte* dstPtr = env->GetByteArrayElements(dstArray, nullptr);
    if (!dstPtr) {
        env->ReleaseByteArrayElements(srcArray, srcPtr, JNI_ABORT);
        return nullptr;
    }

    if (useBilinear) {
        scale_nv21_bilinear(
            reinterpret_cast<uint8_t*>(srcPtr), srcW, srcH,
            reinterpret_cast<uint8_t*>(dstPtr), dstW, dstH);
    } else {
        scale_nv21_nearest(
            reinterpret_cast<uint8_t*>(srcPtr), srcW, srcH,
            reinterpret_cast<uint8_t*>(dstPtr), dstW, dstH);
    }

    env->ReleaseByteArrayElements(srcArray, srcPtr, JNI_ABORT);
    env->ReleaseByteArrayElements(dstArray, dstPtr, 0);

    return dstArray;
}

JNIEXPORT void JNICALL
Java_com_androidvirtualcam_xposed_Nv21Scaler_scaleDirectNative(JNIEnv* env, jclass clazz,
    jobject srcBuffer, jint srcW, jint srcH,
    jobject dstBuffer, jint dstW, jint dstH, jboolean useBilinear) {

    uint8_t* srcPtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(srcBuffer));
    uint8_t* dstPtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(dstBuffer));
    if (!srcPtr || !dstPtr) return;

    if (useBilinear) {
        scale_nv21_bilinear(srcPtr, srcW, srcH, dstPtr, dstW, dstH);
    } else {
        scale_nv21_nearest(srcPtr, srcW, srcH, dstPtr, dstW, dstH);
    }
}

} // extern "C"

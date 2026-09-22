cmake_minimum_required(VERSION 3.22.1)
project("vcam_dsp")

# C++17, C99
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED ON)

# Position independent code for shared libs
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ------------------------------------------------------------------
# Helper: verify file exists at configure time, fail with clear msg
# ------------------------------------------------------------------
function(verify_exists filepath)
    if(NOT EXISTS ${filepath})
        message(FATAL_ERROR "CMakeLists.txt references missing file: ${filepath}")
    endif()
endfunction()

# ------------------------------------------------------------------
# librnnoise.so – lightweight stub implementation
# Physical files present in repo:
#   rnnoise_stub/rnnoise_stub.c
#   rnnoise_stub/rnnoise_data.c
#   rnnoise/include/rnnoise.h
#   rnnoise/arch.h, opus_types.h etc for include path
#   jni/rnnoise_jni.cpp
# Full rnnoise sources are also committed under rnnoise/ for reference,
# but default build uses stub to avoid large model download.
# ------------------------------------------------------------------
set(RNNOISE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/rnnoise)
set(RNNOISE_STUB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/rnnoise_stub)

verify_exists(${RNNOISE_STUB_DIR}/rnnoise_stub.c)
verify_exists(${RNNOISE_STUB_DIR}/rnnoise_data.c)
verify_exists(${CMAKE_CURRENT_SOURCE_DIR}/jni/rnnoise_jni.cpp)
verify_exists(${RNNOISE_DIR}/include/rnnoise.h)

# Stub build – always use stub, no external download
set(RNNOISE_SOURCES
    ${RNNOISE_STUB_DIR}/rnnoise_stub.c
    ${RNNOISE_STUB_DIR}/rnnoise_data.c
    ${CMAKE_CURRENT_SOURCE_DIR}/jni/rnnoise_jni.cpp
)

add_library(rnnoise SHARED ${RNNOISE_SOURCES})

target_include_directories(rnnoise PRIVATE
    ${RNNOISE_DIR}
    ${RNNOISE_DIR}/include
    ${RNNOISE_STUB_DIR}
)

target_compile_definitions(rnnoise PRIVATE
    RNNOISE_BUILD
)

target_compile_options(rnnoise PRIVATE
    -O2
    -fvisibility=hidden
)

target_link_libraries(rnnoise
    android
    log
)

# ------------------------------------------------------------------
# libsoundtouch.so – SoundTouch pitch/tempo
# Physical files present in repo (verified):
#   soundtouch/source/AAFilter.cpp
#   soundtouch/source/FIFOSampleBuffer.cpp
#   soundtouch/source/FIRFilter.cpp
#   soundtouch/source/RateTransposer.cpp
#   soundtouch/source/SoundTouch.cpp
#   soundtouch/source/TDStretch.cpp
#   soundtouch/source/BPMDetect.cpp
#   soundtouch/source/InterpolateCubic.cpp
#   soundtouch/source/InterpolateLinear.cpp
#   soundtouch/source/InterpolateShannon.cpp
#   soundtouch/source/PeakFinder.cpp
#   soundtouch/include/SoundTouch.h etc
#   jni/soundtouch_jni.cpp
# Note: cpu_detect_x86.cpp is x86-specific and excluded for arm64-only build
#       mmx_optimized.cpp and sse_optimized.cpp are also excluded (x86 only)
# ------------------------------------------------------------------
set(SOUNDTOUCH_DIR ${CMAKE_CURRENT_SOURCE_DIR}/soundtouch)

verify_exists(${SOUNDTOUCH_DIR}/source/AAFilter.cpp)
verify_exists(${SOUNDTOUCH_DIR}/source/FIFOSampleBuffer.cpp)
verify_exists(${SOUNDTOUCH_DIR}/source/FIRFilter.cpp)
verify_exists(${SOUNDTOUCH_DIR}/source/RateTransposer.cpp)
verify_exists(${SOUNDTOUCH_DIR}/source/SoundTouch.cpp)
verify_exists(${SOUNDTOUCH_DIR}/source/TDStretch.cpp)
verify_exists(${SOUNDTOUCH_DIR}/source/BPMDetect.cpp)
verify_exists(${SOUNDTOUCH_DIR}/source/InterpolateCubic.cpp)
verify_exists(${SOUNDTOUCH_DIR}/source/InterpolateLinear.cpp)
verify_exists(${SOUNDTOUCH_DIR}/source/InterpolateShannon.cpp)
verify_exists(${SOUNDTOUCH_DIR}/source/PeakFinder.cpp)
verify_exists(${SOUNDTOUCH_DIR}/source/cpu_detect_arm.cpp)
verify_exists(${CMAKE_CURRENT_SOURCE_DIR}/jni/soundtouch_jni.cpp)
verify_exists(${SOUNDTOUCH_DIR}/include/SoundTouch.h)

set(SOUNDTOUCH_SOURCES
    ${SOUNDTOUCH_DIR}/source/AAFilter.cpp
    ${SOUNDTOUCH_DIR}/source/FIFOSampleBuffer.cpp
    ${SOUNDTOUCH_DIR}/source/FIRFilter.cpp
    ${SOUNDTOUCH_DIR}/source/RateTransposer.cpp
    ${SOUNDTOUCH_DIR}/source/SoundTouch.cpp
    ${SOUNDTOUCH_DIR}/source/TDStretch.cpp
    ${SOUNDTOUCH_DIR}/source/BPMDetect.cpp
    ${SOUNDTOUCH_DIR}/source/InterpolateCubic.cpp
    ${SOUNDTOUCH_DIR}/source/InterpolateLinear.cpp
    ${SOUNDTOUCH_DIR}/source/InterpolateShannon.cpp
    ${SOUNDTOUCH_DIR}/source/PeakFinder.cpp
    ${SOUNDTOUCH_DIR}/source/cpu_detect_arm.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/jni/soundtouch_jni.cpp
)

add_library(soundtouch SHARED ${SOUNDTOUCH_SOURCES})

target_include_directories(soundtouch PRIVATE
    ${SOUNDTOUCH_DIR}/include
    ${SOUNDTOUCH_DIR}/source
)

# SoundTouch needs FLOAT_SAMPLES for our pipeline, no exception handling on Android
# Fixed: removed SOUNDTOUCH_INTEGER_SAMPLES=0 which causes #error "conflicting sample types defined"
# in STTypes.h even when set to 0 – STTypes.h treats having both flags defined as conflict
target_compile_definitions(soundtouch PRIVATE
    SOUNDTOUCH_FLOAT_SAMPLES=1
    ST_NO_EXCEPTION_HANDLING=1
    ANDROID
)

target_compile_options(soundtouch PRIVATE
    -O2
    -fvisibility=hidden
    -Wno-unused-parameter
)

target_link_libraries(soundtouch
    android
    log
)

# ------------------------------------------------------------------
# libvcamdsp.so – custom DSP effects (reverb, compressor, EQ, chorus, noise gate)
# Physical files present:
#   vcamdsp/reverb.cpp, compressor.cpp, eq.cpp, chorus.cpp, noise_gate.cpp, vcam_dsp.cpp
#   vcamdsp/*.h
#   jni/vcamdsp_jni.cpp
# Note: Removed OpenSLES linking – not used, was causing link errors on some NDKs
# ------------------------------------------------------------------
set(VCAMDSP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/vcamdsp)

verify_exists(${VCAMDSP_DIR}/reverb.cpp)
verify_exists(${VCAMDSP_DIR}/compressor.cpp)
verify_exists(${VCAMDSP_DIR}/eq.cpp)
verify_exists(${VCAMDSP_DIR}/chorus.cpp)
verify_exists(${VCAMDSP_DIR}/noise_gate.cpp)
verify_exists(${VCAMDSP_DIR}/vcam_dsp.cpp)
verify_exists(${CMAKE_CURRENT_SOURCE_DIR}/jni/vcamdsp_jni.cpp)

set(VCAMDSP_SOURCES
    ${VCAMDSP_DIR}/reverb.cpp
    ${VCAMDSP_DIR}/compressor.cpp
    ${VCAMDSP_DIR}/eq.cpp
    ${VCAMDSP_DIR}/chorus.cpp
    ${VCAMDSP_DIR}/noise_gate.cpp
    ${VCAMDSP_DIR}/vcam_dsp.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/jni/vcamdsp_jni.cpp
)

add_library(vcamdsp SHARED ${VCAMDSP_SOURCES})

target_include_directories(vcamdsp PRIVATE
    ${VCAMDSP_DIR}
    ${RNNOISE_DIR}/include
)

target_compile_options(vcamdsp PRIVATE
    -O2
    -fvisibility=hidden
)

# Only link android and log – OpenSLES removed (was causing failures, not needed)
target_link_libraries(vcamdsp
    android
    log
)

# ------------------------------------------------------------------
# libvcamyuv.so – NEON-optimized YUV scaler
# Physical file: vcamyuv/yuv_scaler.cpp
# ------------------------------------------------------------------
set(VCAMYUV_DIR ${CMAKE_CURRENT_SOURCE_DIR}/vcamyuv)

verify_exists(${VCAMYUV_DIR}/yuv_scaler.cpp)

set(VCAMYUV_SOURCES
    ${VCAMYUV_DIR}/yuv_scaler.cpp
)

add_library(vcamyuv SHARED ${VCAMYUV_SOURCES})

target_include_directories(vcamyuv PRIVATE
    ${VCAMYUV_DIR}
)

target_compile_options(vcamyuv PRIVATE
    -O2
    -fvisibility=hidden
)

target_link_libraries(vcamyuv
    android
    log
)

# NEON flags for ARM64
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_options(vcamyuv PRIVATE -march=armv8-a+simd)
    target_compile_options(rnnoise PRIVATE -march=armv8-a+simd)
    target_compile_options(soundtouch PRIVATE -march=armv8-a+simd)
    target_compile_options(vcamdsp PRIVATE -march=armv8-a+simd)
endif()

# For arm64 only builds, we don't need armeabi-v7a specific flags
# If in future armeabi-v7a is re-added, uncomment:
# if(ANDROID_ABI STREQUAL "armeabi-v7a")
#     target_compile_options(vcamyuv PRIVATE -mfpu=neon)
# endif()

message(STATUS "Configured libraries: rnnoise, soundtouch, vcamdsp, vcamyuv for ABI ${ANDROID_ABI}")

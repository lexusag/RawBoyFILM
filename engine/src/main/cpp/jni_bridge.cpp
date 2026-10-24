#include "rawboy.h"
#include "blemish.h"
#include "burst_merge.h"

#include <android/bitmap.h>
#include <jni.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <memory>
#include <thread>

using rawboy::DecodedImage;

namespace {

void throwJava(JNIEnv* env, const char* cls, const std::string& msg) {
    jclass c = env->FindClass(cls);
    if (c) env->ThrowNew(c, msg.c_str());
}

DecodedImage* fromHandle(jlong handle) { return reinterpret_cast<DecodedImage*>(handle); }

bool readAll(int fd, std::vector<uint8_t>& data, std::string& error) {
    const int own = dup(fd);
    if (own < 0) {
        error = "cannot duplicate file descriptor";
        return false;
    }
    lseek(own, 0, SEEK_SET);  // may fail on pipes; that's fine
    struct stat st{};
    if (fstat(own, &st) == 0 && st.st_size > 0) data.reserve(static_cast<size_t>(st.st_size));

    uint8_t chunk[1 << 16];
    for (;;) {
        const ssize_t n = read(own, chunk, sizeof chunk);
        if (n == 0) break;
        if (n < 0) {
            close(own);
            error = "read failed";
            return false;
        }
        data.insert(data.end(), chunk, chunk + n);
    }
    close(own);
    if (data.empty()) {
        error = "file is empty";
        return false;
    }
    return true;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_app_rawboy_raw_RawNative_open(JNIEnv* env, jclass, jint fd, jfloat extraBlack) {
    try {
        std::vector<uint8_t> data;
        std::string error;
        if (!readAll(fd, data, error)) {
            throwJava(env, "java/io/IOException", error);
            return 0;
        }

        auto image = std::make_unique<DecodedImage>();
        if (!rawboy::decodeAny(data.data(), data.size(), *image, error, extraBlack)) {
            throwJava(env, "java/io/IOException", "Cannot decode file: " + error);
            return 0;
        }
        return reinterpret_cast<jlong>(image.release());
    } catch (const std::bad_alloc&) {
        throwJava(env, "java/lang/OutOfMemoryError", "Not enough memory to decode this file");
    } catch (const std::exception& e) {
        throwJava(env, "java/io/IOException", e.what());
    }
    return 0;
}

/**
 * A HEIC or PNG decoded by Android's ImageDecoder: [bitmap] RGBA_F16 in linear extended sRGB, already upright; [fd]
 * the file, read again for its EXIF.
 */
JNIEXPORT jlong JNICALL Java_app_rawboy_raw_RawNative_openBitmap(JNIEnv* env, jclass, jobject bitmap, jint fd) {
    try {
        std::vector<uint8_t> file;
        std::string error;
        if (fd >= 0 && !readAll(fd, file, error)) file.clear();  // EXIF is optional
        AndroidBitmapInfo info{};
        if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
            info.format != ANDROID_BITMAP_FORMAT_RGBA_F16) {
            throwJava(env, "java/io/IOException", "Cannot decode file: unexpected bitmap format");
            return 0;
        }
        void* pixels = nullptr;
        if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
            throwJava(env, "java/io/IOException", "Cannot decode file: bitmap not readable");
            return 0;
        }
        auto image = std::make_unique<DecodedImage>();
        const bool ok = rawboy::decodeDisplayPixels(pixels, rawboy::DisplayPixels::LinearHalf, static_cast<int>(info.width),
                                                    static_cast<int>(info.height), info.stride, 1,
                                                    file.empty() ? nullptr : file.data(), file.size(), *image, error);
        AndroidBitmap_unlockPixels(env, bitmap);
        if (!ok) {
            throwJava(env, "java/io/IOException", "Cannot decode file: " + error);
            return 0;
        }
        return reinterpret_cast<jlong>(image.release());
    } catch (const std::bad_alloc&) {
        throwJava(env, "java/lang/OutOfMemoryError", "Not enough memory to decode this file");
    } catch (const std::exception& e) {
        throwJava(env, "java/io/IOException", e.what());
    }
    return 0;
}

JNIEXPORT jboolean JNICALL Java_app_rawboy_raw_RawNative_displayReferred(JNIEnv*, jclass, jlong handle) {
    return fromHandle(handle)->displayReferred ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_app_rawboy_raw_RawNative_close(JNIEnv*, jclass, jlong handle) {
    delete fromHandle(handle);
}

JNIEXPORT jint JNICALL Java_app_rawboy_raw_RawNative_width(JNIEnv*, jclass, jlong handle) {
    return fromHandle(handle)->width;
}

JNIEXPORT jint JNICALL Java_app_rawboy_raw_RawNative_height(JNIEnv*, jclass, jlong handle) {
    return fromHandle(handle)->height;
}

JNIEXPORT jdoubleArray JNICALL Java_app_rawboy_raw_RawNative_asShotWhite(JNIEnv* env, jclass, jlong handle) {
    const DecodedImage* img = fromHandle(handle);
    const jdouble xy[2] = {img->whiteX, img->whiteY};
    jdoubleArray result = env->NewDoubleArray(2);
    env->SetDoubleArrayRegion(result, 0, 2, xy);
    return result;
}

JNIEXPORT jfloat JNICALL Java_app_rawboy_raw_RawNative_iso(JNIEnv*, jclass, jlong handle) {
    return fromHandle(handle)->iso;
}

JNIEXPORT jfloat JNICALL Java_app_rawboy_raw_RawNative_baselineExposure(JNIEnv*, jclass, jlong handle) {
    return fromHandle(handle)->baselineExposure;
}

JNIEXPORT jfloat JNICALL Java_app_rawboy_raw_RawNative_clipLevel(JNIEnv*, jclass, jlong handle) {
    return fromHandle(handle)->clipLevel;
}

JNIEXPORT jstring JNICALL Java_app_rawboy_raw_RawNative_make(JNIEnv* env, jclass, jlong handle) {
    return env->NewStringUTF(fromHandle(handle)->metadata.make.c_str());
}

// Exposure essentials for the editor's info line: exposure time (s), f-number, focal length (mm), 35 mm equivalent
// focal length, ISO. NaN (or 0 for ISO) when unknown.
JNIEXPORT jdoubleArray JNICALL Java_app_rawboy_raw_RawNative_shotInfo(JNIEnv* env, jclass, jlong handle) {
    const DecodedImage* img = fromHandle(handle);
    const rawboy::PhotoMetadata& m = img->metadata;
    const jdouble values[5] = {m.exposureTime, m.fNumber, m.focalLength, m.focalLength35mm, img->iso};
    jdoubleArray result = env->NewDoubleArray(5);
    env->SetDoubleArrayRegion(result, 0, 5, values);
    return result;
}

JNIEXPORT jstring JNICALL Java_app_rawboy_raw_RawNative_camera(JNIEnv* env, jclass, jlong handle) {
    return env->NewStringUTF(fromHandle(handle)->camera.c_str());
}

JNIEXPORT jstring JNICALL Java_app_rawboy_raw_RawNative_describe(JNIEnv* env, jclass, jlong handle) {
    const DecodedImage* img = fromHandle(handle);
    const std::string text = img->camera + " · " + std::to_string(img->width) + "×" +
                             std::to_string(img->height) + " · " + img->decoder;
    return env->NewStringUTF(text.c_str());
}

// Finds blemish candidates on skin. [mask]: direct FloatBuffer of maskW*maskH skin
// probabilities. Returns (x, y, radius, score) per spot, most obvious first.
JNIEXPORT jfloatArray JNICALL Java_app_rawboy_raw_RawNative_detectBlemishes(JNIEnv* env, jclass, jlong handle,
                                                                             jobject mask, jint maskW, jint maskH,
                                                                             jfloat faceDiameter) {
    const DecodedImage* img = fromHandle(handle);
    const auto* data = static_cast<const float*>(env->GetDirectBufferAddress(mask));
    if (!data || env->GetDirectBufferCapacity(mask) < static_cast<jlong>(maskW) * maskH) {
        throwJava(env, "java/lang/IllegalArgumentException", "invalid skin mask");
        return nullptr;
    }
    const auto spots = rawboy::detectBlemishes(*img, {data, maskW, maskH}, faceDiameter);
    std::vector<float> flat;
    flat.reserve(spots.size() * 4);
    for (const auto& s : spots) flat.insert(flat.end(), {s.x, s.y, s.radius, s.score});
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(flat.size()));
    if (!flat.empty()) env->SetFloatArrayRegion(result, 0, static_cast<jsize>(flat.size()), flat.data());
    return result;
}

// Undoes any previous healing, then heals the given spots (x, y, radius, score)*n.
JNIEXPORT void JNICALL Java_app_rawboy_raw_RawNative_healBlemishes(JNIEnv* env, jclass, jlong handle,
                                                                    jfloatArray spots) {
    DecodedImage* img = fromHandle(handle);
    rawboy::restoreBackups(*img, img->healBackups);
    const jsize n = env->GetArrayLength(spots);
    std::vector<float> v(n);
    if (n) env->GetFloatArrayRegion(spots, 0, n, v.data());
    for (jsize i = 0; i + 3 < n; i += 4) rawboy::healBlemish(*img, {v[i], v[i + 1], v[i + 2], v[i + 3]}, img->healBackups);
}

// Lens data for the GPU, as doubles:
// [0] orientation, [1] sensorW, [2] sensorH,
// [3] hasWarp, [4] warpPlanes, [5..22] 3x(kr0..kr3, kt0, kt1), [23] cx, [24] cy,
// [25] hasVignette, [26..30] k0..k4, [31] cx, [32] cy,
// [33] hasShading, [34] pointsV, [35] pointsH, [36] originV, [37] originH, [38] spacingV, [39] spacingH,
// [40] gain maps applied at decode (count)
JNIEXPORT jdoubleArray JNICALL Java_app_rawboy_raw_RawNative_lensInfo(JNIEnv* env, jclass, jlong handle) {
    const DecodedImage* img = fromHandle(handle);
    const rawboy::LensOpcodes& l = img->lens;
    jdouble v[41] = {};
    v[0] = img->orientation;
    v[1] = img->sensorWidth;
    v[2] = img->sensorHeight;
    v[3] = l.hasWarp;
    v[4] = l.warp.planes;
    for (int p = 0; p < 3; ++p)
        for (int k = 0; k < 6; ++k) v[5 + p * 6 + k] = l.warp.coef[p][k];
    v[23] = l.warp.centerX;
    v[24] = l.warp.centerY;
    v[25] = l.hasVignette;
    for (int k = 0; k < 5; ++k) v[26 + k] = l.vignette.k[k];
    v[31] = l.vignette.centerX;
    v[32] = l.vignette.centerY;
    const rawboy::ShadingGrid& s = img->shading;
    v[33] = !s.empty();
    v[34] = s.pointsV;
    v[35] = s.pointsH;
    v[36] = s.originV;
    v[37] = s.originH;
    v[38] = s.spacingV;
    v[39] = s.spacingH;
    v[40] = static_cast<double>(l.gainMaps.size());
    jdoubleArray result = env->NewDoubleArray(41);
    env->SetDoubleArrayRegion(result, 0, 41, v);
    return result;
}

JNIEXPORT jfloatArray JNICALL Java_app_rawboy_raw_RawNative_lensShading(JNIEnv* env, jclass, jlong handle) {
    const rawboy::ShadingGrid& s = fromHandle(handle)->shading;
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(s.gains.size()));
    if (!s.gains.empty()) env->SetFloatArrayRegion(result, 0, static_cast<jsize>(s.gains.size()), s.gains.data());
    return result;
}

JNIEXPORT jstring JNICALL Java_app_rawboy_raw_RawNative_lensName(JNIEnv* env, jclass, jlong handle) {
    return env->NewStringUTF(fromHandle(handle)->lensName.c_str());
}

// Area-average downscale into a direct buffer of dstW*dstH RGB half floats.
// With boostHighlights, each full-resolution pixel near/above clipping is scaled up
// first (spektrafilm-style pre-clip reconstruction), so small light sources keep
// their energy in the bloom source images. Must match bloomBoost() in Shaders.kt.
JNIEXPORT void JNICALL Java_app_rawboy_raw_RawNative_downsample(JNIEnv* env, jclass, jlong handle, jint dstW,
                                                                 jint dstH, jobject buffer, jboolean boostHighlights) {
    const DecodedImage* img = fromHandle(handle);
    auto* dst = static_cast<uint16_t*>(env->GetDirectBufferAddress(buffer));
    if (!dst || env->GetDirectBufferCapacity(buffer) < static_cast<jlong>(dstW) * dstH * 6) {
        throwJava(env, "java/lang/IllegalArgumentException", "preview buffer too small");
        return;
    }
    rawboy::downsample(*img, dstW, dstH, dst, boostHighlights);
}

// Copies a full-resolution tile (RGB half floats, tightly packed) into a direct buffer.
JNIEXPORT void JNICALL Java_app_rawboy_raw_RawNative_copyTile(JNIEnv* env, jclass, jlong handle, jint x, jint y,
                                                               jint w, jint h, jobject buffer) {
    const DecodedImage* img = fromHandle(handle);
    auto* dst = static_cast<uint16_t*>(env->GetDirectBufferAddress(buffer));
    if (!dst || env->GetDirectBufferCapacity(buffer) < static_cast<jlong>(w) * h * 6 || x < 0 || y < 0 ||
        x + w > img->width || y + h > img->height) {
        throwJava(env, "java/lang/IllegalArgumentException", "invalid tile");
        return;
    }
    rawboy::copyTile(*img, x, y, w, h, dst);
}

// [luma, chroma] noise std in the denoise domain (rawboy::estimateNoise); zeros if not measured.
JNIEXPORT jfloatArray JNICALL Java_app_rawboy_raw_RawNative_noiseLevel(JNIEnv* env, jclass, jlong handle) {
    const rawboy::NoiseLevel n = rawboy::estimateNoise(*fromHandle(handle));
    const jfloat v[2] = {n.luma, n.chroma};
    jfloatArray result = env->NewFloatArray(2);
    env->SetFloatArrayRegion(result, 0, 2, v);
    return result;
}

// ---- Burst merge (RawBoy Camera; BurstNative.kt) ----

namespace {
rawboy::BurstMerger* burstOf(jlong handle) { return reinterpret_cast<rawboy::BurstMerger*>(handle); }
}  // namespace

// A merger whose frames wait for setLevels (the reference's own levels) before they merge.
JNIEXPORT jlong JNICALL Java_app_rawboy_raw_BurstNative_create(JNIEnv* env, jclass, jint width, jint height,
                                                              jfloatArray black, jfloat white) {
    if (width < 2 || height < 2 || env->GetArrayLength(black) != 4) {
        throwJava(env, "java/lang/IllegalArgumentException", "invalid burst geometry");
        return 0;
    }
    float b[4];
    env->GetFloatArrayRegion(black, 0, 4, b);
    return reinterpret_cast<jlong>(new rawboy::BurstMerger(width, height, b, white, 0.0f, 0.0f, false));
}

// Copies one RAW_SENSOR plane (direct buffer, rowStride bytes per row); returns its index (0 = the reference).
JNIEXPORT jint JNICALL Java_app_rawboy_raw_BurstNative_addFrame(JNIEnv* env, jclass, jlong handle, jobject plane,
                                                               jint width, jint height, jint rowStride) {
    const auto* data = static_cast<const uint8_t*>(env->GetDirectBufferAddress(plane));
    if (!data || rowStride < width * 2 ||
        env->GetDirectBufferCapacity(plane) < static_cast<jlong>(rowStride) * (height - 1) + width * 2) {
        throwJava(env, "java/lang/IllegalArgumentException", "invalid raw plane");
        return -1;
    }
    return burstOf(handle)->addFrame(data, rowStride, 0.0);
}

// A frame's exposure (time x ISO), once its capture result is in.
JNIEXPORT void JNICALL Java_app_rawboy_raw_BurstNative_setGain(JNIEnv*, jclass, jlong handle, jint frame,
                                                              jdouble gain) {
    burstOf(handle)->setGain(frame, gain);
}

// The reference's black levels per 2x2 CFA position (null: keep create's) and noise model (0, 0 = estimate).
JNIEXPORT void JNICALL Java_app_rawboy_raw_BurstNative_setLevels(JNIEnv* env, jclass, jlong handle, jfloatArray black,
                                                                jfloat noiseScale, jfloat noiseOffset) {
    float b[4];
    const bool hasBlack = black && env->GetArrayLength(black) == 4;
    if (hasBlack) env->GetFloatArrayRegion(black, 0, 4, b);
    burstOf(handle)->setLevels(hasBlack ? b : nullptr, noiseScale, noiseOffset);
}

// Merges what is still waiting; returns a direct buffer over the merged 16-bit samples, valid until destroy.
JNIEXPORT jobject JNICALL Java_app_rawboy_raw_BurstNative_finish(JNIEnv* env, jclass, jlong handle, jint width,
                                                                jint height) {
    const uint16_t* merged = burstOf(handle)->finish();
    if (!merged) {
        throwJava(env, "java/lang/IllegalStateException", "no frames");
        return nullptr;
    }
    return env->NewDirectByteBuffer(const_cast<uint16_t*>(merged), static_cast<jlong>(width) * height * 2);
}

// Mean tile weight of each merged frame after the reference (0 = rejected everywhere, 1 = fully used).
JNIEXPORT jfloatArray JNICALL Java_app_rawboy_raw_BurstNative_frameWeights(JNIEnv* env, jclass, jlong handle) {
    const std::vector<float> w = burstOf(handle)->frameWeights();
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(w.size()));
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(w.size()), w.data());
    return result;
}

// Most frames that waited at once (copied, not yet merged).
JNIEXPORT jint JNICALL Java_app_rawboy_raw_BurstNative_maxQueued(JNIEnv*, jclass, jlong handle) {
    return burstOf(handle)->maxQueued();
}

JNIEXPORT void JNICALL Java_app_rawboy_raw_BurstNative_destroy(JNIEnv*, jclass, jlong handle) {
    delete burstOf(handle);
}

}  // extern "C"

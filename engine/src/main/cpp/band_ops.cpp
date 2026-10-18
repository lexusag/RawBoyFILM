// Per-pixel work of the export path in C++ (Kotlin loops cost ~1 s per 12 MP photo in debug builds):
// repacking the GPU's RGBA uint32 tiles into RGB 16-bit bands, and the streaming area downscaler
// (BandDownscaler.kt is the Kotlin side). Built into librawboyjpeg.so with the jpegli encoder.

#include "band_ops.h"

#include <jni.h>

namespace {

using rawboy::Downscaler;
using rawboy::pack;

Downscaler* fromHandle(jlong handle) { return reinterpret_cast<Downscaler*>(handle); }

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_app_rawboy_export_BandNative_createDownscaler(JNIEnv*, jclass, jint inW, jint inH,
                                                                            jint outW, jint outH) {
    return reinterpret_cast<jlong>(new Downscaler(inW, inH, outW, outH));
}

JNIEXPORT jint JNICALL Java_app_rawboy_export_BandNative_maxOutputRows(JNIEnv*, jclass, jlong handle, jint inputRows) {
    return fromHandle(handle)->maxOutputRows(inputRows);
}

JNIEXPORT jint JNICALL Java_app_rawboy_export_BandNative_push(JNIEnv* env, jclass, jlong handle, jshortArray in,
                                                               jint rows, jshortArray out) {
    auto* src = static_cast<uint16_t*>(env->GetPrimitiveArrayCritical(in, nullptr));
    auto* dst = static_cast<uint16_t*>(env->GetPrimitiveArrayCritical(out, nullptr));
    int written = 0;
    if (src && dst) written = fromHandle(handle)->push(src, rows, dst);
    if (dst) env->ReleasePrimitiveArrayCritical(out, dst, 0);
    if (src) env->ReleasePrimitiveArrayCritical(in, src, JNI_ABORT);
    return written;
}

JNIEXPORT jint JNICALL Java_app_rawboy_export_BandNative_finish(JNIEnv* env, jclass, jlong handle, jshortArray out) {
    auto* dst = static_cast<uint16_t*>(env->GetPrimitiveArrayCritical(out, nullptr));
    int written = dst ? fromHandle(handle)->finish(dst) : 0;
    if (dst) env->ReleasePrimitiveArrayCritical(out, dst, 0);
    return written;
}

JNIEXPORT void JNICALL Java_app_rawboy_export_BandNative_destroy(JNIEnv*, jclass, jlong handle) {
    delete fromHandle(handle);
}

JNIEXPORT void JNICALL Java_app_rawboy_export_BandNative_packTile(JNIEnv* env, jclass, jobject tile, jint bytesPerSample,
                                                                   jint cols, jint rows, jshortArray band, jint bandWidth,
                                                                   jint x) {
    const void* src = env->GetDirectBufferAddress(tile);
    if (!src) return;
    auto* dst = static_cast<uint16_t*>(env->GetPrimitiveArrayCritical(band, nullptr));
    if (!dst) return;
    if (bytesPerSample == 2) {
        pack(static_cast<const uint16_t*>(src), cols, rows, dst, bandWidth, x);
    } else {
        pack(static_cast<const uint32_t*>(src), cols, rows, dst, bandWidth, x);
    }
    env->ReleasePrimitiveArrayCritical(band, dst, 0);
}

}  // extern "C"

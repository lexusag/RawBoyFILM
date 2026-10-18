// JPEG encoding with jpegli (libjxl's libjpeg-compatible encoder): adaptive quantisation
// gives fewer artefacts per byte than libjpeg, and it takes 16-bit samples, so the
// developed image is quantised once, by the DCT, instead of first to 8 bits.
//
// Built into librawboyjpeg.so (see jpegli.cmake). Used by Exporter for JPEG when ExportSettings.jpegEncoder is JPEGLI. Rows are streamed in
// bands as the GPU develops them, so no full-size bitmap is needed.

#include <jni.h>
#include <unistd.h>

#include <cstdio>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#include "jpegli_settings.h"

namespace {

void throwJava(JNIEnv* env, const std::string& msg) {
    jclass c = env->FindClass("java/io/IOException");
    if (c) env->ThrowNew(c, msg.c_str());
}

/** jpegli reports fatal errors through error_exit, which must not return: turn them into exceptions. */
[[noreturn]] void errorExit(j_common_ptr cinfo) {
    char message[JMSG_LENGTH_MAX];
    (*cinfo->err->format_message)(cinfo, message);
    throw std::runtime_error(message);
}

struct Encoder {
    jpeg_compress_struct cinfo{};
    jpeg_error_mgr err{};
    FILE* file = nullptr;
    int width = 0;
    bool created = false;

    ~Encoder() {
        if (created) jpegli_destroy_compress(&cinfo);
        if (file) fclose(file);
    }
};

Encoder* fromHandle(jlong handle) { return reinterpret_cast<Encoder*>(handle); }

}  // namespace

extern "C" {

/**
 * Starts a [width] x [height] RGB JPEG at [quality] (1..100), 4:4:4, written to [fd]
 * (duplicated, so the caller may close its own). Returns a handle for writeRows/finish/abort.
 */
JNIEXPORT jlong JNICALL Java_app_rawboy_export_JpegNative_begin(JNIEnv* env, jclass, jint fd, jint width,
                                                                 jint height, jint quality) {
    auto enc = std::make_unique<Encoder>();
    const int own = dup(fd);
    if (own < 0 || !(enc->file = fdopen(own, "wb"))) {
        if (own >= 0) close(own);
        throwJava(env, "cannot open output for JPEG");
        return 0;
    }
    try {
        enc->cinfo.err = jpegli_std_error(&enc->err);
        enc->err.error_exit = errorExit;
        jpegli_create_compress(&enc->cinfo);
        enc->created = true;
        jpegli_stdio_dest(&enc->cinfo, enc->file);
        rawboy::configureJpegli(&enc->cinfo, width, height, quality);
        jpegli_start_compress(&enc->cinfo, TRUE);
        enc->width = width;
        return reinterpret_cast<jlong>(enc.release());
    } catch (const std::exception& e) {
        throwJava(env, std::string("JPEG encoder: ") + e.what());
        return 0;
    }
}

/** Appends [rows] rows of interleaved RGB, unsigned 16-bit (0..65535, display-encoded) samples. */
JNIEXPORT void JNICALL Java_app_rawboy_export_JpegNative_writeRows(JNIEnv* env, jclass, jlong handle,
                                                                    jshortArray samples, jint rows) {
    Encoder* enc = fromHandle(handle);
    const size_t needed = static_cast<size_t>(enc->width) * 3 * static_cast<size_t>(rows);
    if (static_cast<size_t>(env->GetArrayLength(samples)) < needed) {
        throwJava(env, "JPEG encoder: band too small");
        return;
    }
    auto* data = static_cast<uint16_t*>(env->GetPrimitiveArrayCritical(samples, nullptr));
    if (!data) return;
    std::string error;
    try {
        std::vector<JSAMPROW> pointers(static_cast<size_t>(rows));
        for (int r = 0; r < rows; ++r) {
            pointers[r] = reinterpret_cast<JSAMPROW>(data + static_cast<size_t>(r) * enc->width * 3);
        }
        JDIMENSION written = 0;
        while (written < static_cast<JDIMENSION>(rows)) {
            written += jpegli_write_scanlines(&enc->cinfo, pointers.data() + written, rows - written);
        }
    } catch (const std::exception& e) {
        error = e.what();
    }
    env->ReleasePrimitiveArrayCritical(samples, data, JNI_ABORT);
    if (!error.empty()) throwJava(env, "JPEG encoder: " + error);
}

/** Completes the file and frees the encoder; the handle is invalid afterwards. */
JNIEXPORT void JNICALL Java_app_rawboy_export_JpegNative_finish(JNIEnv* env, jclass, jlong handle) {
    std::unique_ptr<Encoder> enc(fromHandle(handle));
    std::string error;
    try {
        jpegli_finish_compress(&enc->cinfo);
    } catch (const std::exception& e) {
        error = e.what();
    }
    const bool flushed = fflush(enc->file) == 0;
    if (!error.empty()) {
        throwJava(env, "JPEG encoder: " + error);
    } else if (!flushed) {
        throwJava(env, "JPEG write failed");
    }
}

/** Frees the encoder without completing the file; the handle is invalid afterwards. */
JNIEXPORT void JNICALL Java_app_rawboy_export_JpegNative_abort(JNIEnv*, jclass, jlong handle) {
    delete fromHandle(handle);
}

}  // extern "C"

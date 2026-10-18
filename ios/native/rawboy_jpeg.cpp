// RawBoy iOS: the export helpers' C API (rawboy_jpeg.h), the iOS counterpart of jpeg_jpegli.cpp and band_ops.cpp.

#include "rawboy_jpeg.h"

#include "band_ops.h"
#include "jpegli_settings.h"

#include <cstdio>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

struct RBDownscaler {
    rawboy::Downscaler impl;
};

namespace {

void setError(char* error, size_t size, const std::string& message) {
    if (error && size) std::snprintf(error, size, "%s", message.c_str());
}

/** jpegli reports fatal errors through error_exit, which must not return: turn them into exceptions. */
[[noreturn]] void errorExit(j_common_ptr cinfo) {
    char message[JMSG_LENGTH_MAX];
    (*cinfo->err->format_message)(cinfo, message);
    throw std::runtime_error(message);
}

}  // namespace

struct RBJpegEncoder {
    jpeg_compress_struct cinfo{};
    jpeg_error_mgr err{};
    FILE* file = nullptr;
    int width = 0;
    bool created = false;

    ~RBJpegEncoder() {
        if (created) jpegli_destroy_compress(&cinfo);
        if (file) std::fclose(file);
    }
};

extern "C" {

RBDownscaler* rb_downscaler_create(int inWidth, int inHeight, int outWidth, int outHeight) {
    return new RBDownscaler{rawboy::Downscaler(inWidth, inHeight, outWidth, outHeight)};
}

int rb_downscaler_max_output_rows(const RBDownscaler* d, int inputRows) { return d->impl.maxOutputRows(inputRows); }
int rb_downscaler_push(RBDownscaler* d, const uint16_t* in, int rows, uint16_t* out) { return d->impl.push(in, rows, out); }
int rb_downscaler_finish(RBDownscaler* d, uint16_t* out) { return d->impl.finish(out); }
void rb_downscaler_destroy(RBDownscaler* d) { delete d; }

void rb_pack_tile(const void* tile, int bytesPerSample, int cols, int rows, uint16_t* band, int bandWidth, int x) {
    if (bytesPerSample == 2) {
        rawboy::pack(static_cast<const uint16_t*>(tile), cols, rows, band, bandWidth, x);
    } else {
        rawboy::pack(static_cast<const uint32_t*>(tile), cols, rows, band, bandWidth, x);
    }
}

RBJpegEncoder* rb_jpeg_begin(const char* path, int width, int height, int quality, char* error, size_t errorSize) {
    auto enc = std::make_unique<RBJpegEncoder>();
    if (!(enc->file = std::fopen(path, "wb"))) {
        setError(error, errorSize, "cannot open output for JPEG");
        return nullptr;
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
        return enc.release();
    } catch (const std::exception& e) {
        setError(error, errorSize, std::string("JPEG encoder: ") + e.what());
        return nullptr;
    }
}

bool rb_jpeg_write_rows(RBJpegEncoder* enc, const uint16_t* samples, int rows, char* error, size_t errorSize) {
    try {
        std::vector<JSAMPROW> pointers(static_cast<size_t>(rows));
        for (int r = 0; r < rows; ++r) {
            pointers[r] = reinterpret_cast<JSAMPROW>(const_cast<uint16_t*>(samples) +
                                                     static_cast<size_t>(r) * enc->width * 3);
        }
        JDIMENSION written = 0;
        while (written < static_cast<JDIMENSION>(rows)) {
            written += jpegli_write_scanlines(&enc->cinfo, pointers.data() + written, rows - written);
        }
        return true;
    } catch (const std::exception& e) {
        setError(error, errorSize, std::string("JPEG encoder: ") + e.what());
        return false;
    }
}

bool rb_jpeg_finish(RBJpegEncoder* encoder, char* error, size_t errorSize) {
    std::unique_ptr<RBJpegEncoder> enc(encoder);
    try {
        jpegli_finish_compress(&enc->cinfo);
    } catch (const std::exception& e) {
        setError(error, errorSize, std::string("JPEG encoder: ") + e.what());
        return false;
    }
    if (std::fflush(enc->file) != 0) {
        setError(error, errorSize, "JPEG write failed");
        return false;
    }
    return true;
}

void rb_jpeg_abort(RBJpegEncoder* encoder) { delete encoder; }

}  // extern "C"

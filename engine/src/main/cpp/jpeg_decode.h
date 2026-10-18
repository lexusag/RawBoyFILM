// JPEG decoding with jpegli, exported by the JPEG library (librawboyjpeg.so / RawBoyJpeg / the web module) for the
// decoders (decoder_display.cpp): jpegli lives in its own library because of the Highway clash with libjxl, so the
// decoder library calls into it through this C function instead of linking jpegli itself.

#pragma once

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define RB_JPEG_DECODE_API __attribute__((visibility("default")))

typedef struct RBJpegSink {
    void* context;
    /** Called once with the image size before any rows; return 0 to stop decoding. */
    int (*begin)(void* context, int width, int height);
    /**
     * [rows] rows starting at row [y]: interleaved RGB, unsigned 16-bit samples (0..65535, as coded: the file's
     * transfer curve and primaries). Grayscale files come as R = G = B. Return 0 to stop decoding.
     */
    int (*rows)(void* context, int y, int rows, const uint16_t* rgb);
} RBJpegSink;

/**
 * Decodes the JPEG in [data] into [sink], a band of rows at a time. jpegli reconstructs at more than 8-bit
 * precision, which matters once the tone curve is inverted. Returns false with a message in [error] on failure
 * (CMYK files are refused).
 */
RB_JPEG_DECODE_API bool rb_jpeg_decode(const uint8_t* data, size_t size, const RBJpegSink* sink, char* error,
                                       size_t errorSize);

#ifdef __cplusplus
}
#endif

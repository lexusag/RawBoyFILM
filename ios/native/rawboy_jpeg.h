// RawBoy iOS: C API of the export's native helpers (RawBoyJpeg.framework): the jpegli encoder and the band
// operations, the iOS counterpart of jpeg_jpegli.cpp and band_ops.cpp. A separate framework, as on Android
// (librawboyjpeg.so), because jpegli and the DNG SDK's libjxl bundle different Highway versions.

#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define RB_JPEG_API __attribute__((visibility("default")))

/** Exact area-average downscaler streamed row by row (rawboy::Downscaler; BandDownscaler.kt is the contract). */
typedef struct RBDownscaler RBDownscaler;

RB_JPEG_API RBDownscaler* rb_downscaler_create(int inWidth, int inHeight, int outWidth, int outHeight);
/** Most output rows one push of [inputRows] rows can produce (size of [out] in rows). */
RB_JPEG_API int rb_downscaler_max_output_rows(const RBDownscaler* downscaler, int inputRows);
/** Adds [rows] rows of RGB 16-bit samples; writes finished output rows to [out] and returns their count. */
RB_JPEG_API int rb_downscaler_push(RBDownscaler* downscaler, const uint16_t* in, int rows, uint16_t* out);
/** Writes the remaining output rows to [out]; returns their count. */
RB_JPEG_API int rb_downscaler_finish(RBDownscaler* downscaler, uint16_t* out);
RB_JPEG_API void rb_downscaler_destroy(RBDownscaler* downscaler);

/**
 * Developed tile ([cols] x [rows] RGBA samples, [bytesPerSample] 2 or 4) -> RGB 16-bit [band] ([bandWidth] pixels
 * wide) at column [x].
 */
RB_JPEG_API void rb_pack_tile(const void* tile, int bytesPerSample, int cols, int rows, uint16_t* band, int bandWidth,
                              int x);

/** jpegli JPEG writer: 16-bit RGB in, 4:4:4, written to a file. */
typedef struct RBJpegEncoder RBJpegEncoder;

RB_JPEG_API RBJpegEncoder* rb_jpeg_begin(const char* path, int width, int height, int quality, char* error,
                                         size_t errorSize);
/** Appends [rows] rows of interleaved RGB unsigned 16-bit (0..65535, display-encoded) samples. */
RB_JPEG_API bool rb_jpeg_write_rows(RBJpegEncoder* encoder, const uint16_t* samples, int rows, char* error,
                                    size_t errorSize);
/** Completes the file and frees the encoder (also on failure). */
RB_JPEG_API bool rb_jpeg_finish(RBJpegEncoder* encoder, char* error, size_t errorSize);
/** Frees the encoder without completing the file. */
RB_JPEG_API void rb_jpeg_abort(RBJpegEncoder* encoder);

#ifdef __cplusplus
}
#endif

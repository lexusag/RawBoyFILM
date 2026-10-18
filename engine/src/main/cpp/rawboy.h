#pragma once

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

#include "lens.h"

namespace rawboy {

/** Original pixels under a healed blemish, so healing can be undone. */
struct HealBackup {
    int x, y, width, height;
    std::vector<uint16_t> pixels;  // RGB half floats
};

/**
 * The photo's EXIF essentials, read by the decoder, for exports that can't copy EXIF from the file themselves
 * (RawBoy Web; Android copies it with ExifInterface). Empty strings and NaN mean unknown.
 */
struct PhotoMetadata {
    std::string make, model, lensMake, lensModel, artist, copyright;
    std::string dateTimeOriginal;  // "YYYY:MM:DD HH:MM:SS"
    std::string subSecTimeOriginal, offsetTimeOriginal;
    double exposureTime = NAN;  // seconds
    double fNumber = NAN;
    double exposureBias = NAN;  // EV
    double focalLength = NAN;   // mm
    double focalLength35mm = NAN;
    float iso = 0;
    bool hasGps = false;
    double gpsLatitude[3] = {}, gpsLongitude[3] = {};  // degrees, minutes, seconds
    char gpsLatitudeRef = 'N', gpsLongitudeRef = 'E';
    double gpsAltitude = NAN;  // metres
    bool gpsBelowSeaLevel = false;
};

// Scene-referred image handed to the GPU pipeline.
// Pixels are interleaved RGB half floats in linear Rec.2020 (D65), already
// white balanced for the as-shot illuminant and oriented for display.
// 1.0 is the sensor clipping level.
struct DecodedImage {
    int width = 0;
    int height = 0;
    std::vector<uint16_t> rgb;
    double whiteX = 0.3127;  // as-shot white chromaticity (CIE xy)
    double whiteY = 0.3290;
    std::string decoder;
    std::string camera;
    std::string lensName;
    float iso = 0;  // ISO speed from the file, 0 if unknown
    // DNG BaselineExposure in EV, already applied to rgb; NaN if the file has none. Phones store their own auto
    // exposure decision here (the raw itself is exposed to protect highlights).
    float baselineExposure = NAN;
    PhotoMetadata metadata;

    // Geometry before orientation, so sensor-space lens corrections can be mapped.
    int sensorWidth = 0;
    int sensorHeight = 0;
    int orientation = 1;  // TIFF orientation applied to reach width x height

    // Embedded lens corrections. Gain maps are already applied to rgb; the warp,
    // radial vignette and luminance shading are applied (or undone) on the GPU.
    LensOpcodes lens;
    ShadingGrid shading;  // luminance (green) gain map, empty if none

    // A JPEG/HEIC taken back to scene light by display_inverse.h; rendered with uDisplaySource (AgX per channel).
    bool displayReferred = false;

    // Blemish healing applied to rgb, newest last (see blemish.cpp).
    std::vector<HealBackup> healBackups;
};

/** Runs fn(rowBegin, rowEnd) on up to 8 threads. */
template <typename Fn>
void parallelRows(int rows, Fn&& fn) {
    const int threads = std::max(1, std::min(8, static_cast<int>(std::thread::hardware_concurrency())));
    std::vector<std::thread> pool;
    const int step = (rows + threads - 1) / threads;
    for (int t = 0; t < threads; ++t) {
        const int begin = t * step;
        const int end = std::min(rows, begin + step);
        if (begin >= end) break;
        pool.emplace_back([&fn, begin, end] { fn(begin, end); });
    }
    for (auto& th : pool) th.join();
}

/** Float -> half float bits; negatives and NaN -> 0, saturates at 65504, rounds to nearest even. */
inline uint16_t floatToHalf(float f) {
#if defined(__aarch64__)
    // The hardware conversion (FCVT), much faster than the bit arithmetic below and bit-identical to it
    // (checked over 44M float patterns, subnormals and saturation included).
    f = f > 0.0f ? (f < 65504.0f ? f : 65504.0f) : 0.0f;  // the comparison is false for NaN -> 0
    const __fp16 h = static_cast<__fp16>(f);
    uint16_t bits;
    std::memcpy(&bits, &h, sizeof bits);
    return bits;
#else
    if (!(f > 0.0f)) return 0;          // negatives and NaN -> 0
    if (f > 65504.0f) f = 65504.0f;     // largest finite half
    uint32_t x;
    std::memcpy(&x, &f, sizeof x);
    int32_t exp = static_cast<int32_t>((x >> 23) & 0xff) - 127 + 15;
    uint32_t mant = x & 0x7fffff;
    if (exp <= 0) {  // subnormal half
        if (exp < -10) return 0;
        mant |= 0x800000;
        const uint32_t shift = static_cast<uint32_t>(14 - exp);
        uint32_t h = mant >> shift;
        const uint32_t rem = mant & ((1u << shift) - 1);
        const uint32_t halfway = 1u << (shift - 1);
        if (rem > halfway || (rem == halfway && (h & 1))) ++h;
        return static_cast<uint16_t>(h);
    }
    uint32_t h = (static_cast<uint32_t>(exp) << 10) | (mant >> 13);
    const uint32_t rem = mant & 0x1fff;
    if (rem > 0x1000 || (rem == 0x1000 && (h & 1))) ++h;
    return static_cast<uint16_t>(h);
#endif
}

inline float halfToFloat(uint16_t h) {
    const uint32_t sign = static_cast<uint32_t>(h & 0x8000) << 16;
    const uint32_t exp = (h >> 10) & 0x1f;
    const uint32_t mant = h & 0x3ff;
    if (exp == 0) {
        const float f = static_cast<float>(mant) * (1.0f / 16777216.0f);
        return sign ? -f : f;
    }
    uint32_t bits;
    if (exp == 31) {
        bits = sign | 0x7f800000u | (mant << 13);
    } else {
        bits = sign | ((exp + 112) << 23) | (mant << 13);
    }
    float f;
    std::memcpy(&f, &bits, sizeof f);
    return f;
}

// extraBlack: black to subtract beyond the file's, as a fraction of the white range (0 = none).
bool decodeWithLibRaw(const uint8_t* data, size_t size, DecodedImage& out, std::string& error, float extraBlack = 0.0f);

/**
 * Decodes a JPEG (decodeJpeg), or a raw with LibRaw, falling back to the Adobe DNG SDK (when built in) for DNGs
 * LibRaw can't read, and to decodeTiff for TIFFs neither takes.
 * On failure, [error] holds both decoders' reasons.
 */
bool decodeAny(const uint8_t* data, size_t size, DecodedImage& out, std::string& error, float extraBlack = 0.0f);

bool looksLikeJpeg(const uint8_t* data, size_t size);

/** A JPEG (its ICC profile or sRGB; Ultra HDR gain map restored near white) as a display source. */
bool decodeJpeg(const uint8_t* data, size_t size, DecodedImage& out, std::string& error);

/**
 * A plain image TIFF (8/16-bit RGB or gray; strips or tiles; none, LZW, Deflate or PackBits; ICC, orientation, EXIF)
 * as a display source. decodeAny tries it only after the raw decoders, since most raws are TIFF files too.
 */
bool decodeTiff(const uint8_t* data, size_t size, DecodedImage& out, std::string& error);

/** RGBA pixel layouts a platform decoder hands over. */
enum class DisplayPixels {
    LinearHalf,   // linear extended sRGB, half floats (Android's RGBA_F16 bitmaps)
    LinearFloat,  // linear extended sRGB, floats (iOS ImageIO)
    Srgb8,        // sRGB-encoded bytes (a browser canvas)
    DisplayP3_8,  // Display P3-encoded bytes (a browser canvas in display-p3)
};

/**
 * A platform-decoded display image (HEIC or PNG, SDR): [pixels] RGBA in [format], [rowBytes] apart. [orientation]
 * (1..8) is still to be applied (1 when the platform already turned the pixels upright). [file] (optional): the HEIF or
 * PNG file, for its EXIF.
 */
bool decodeDisplayPixels(const void* pixels, DisplayPixels format, int width, int height, size_t rowBytes,
                         int orientation, const uint8_t* file, size_t fileSize, DecodedImage& out, std::string& error);

#if RAWBOY_WITH_DNG_SDK
bool decodeWithDngSdk(const uint8_t* data, size_t size, DecodedImage& out, std::string& error);
#endif

/**
 * Area-average downscale into [dstW] x [dstH] RGB half floats. With [boostHighlights], each full-resolution pixel
 * near/above clipping is scaled up first (spektrafilm-style pre-clip reconstruction), so small light sources keep
 * their energy in the bloom source images. Must match bloomBoost() in the shader.
 */
void downsample(const DecodedImage& img, int dstW, int dstH, uint16_t* dst, bool boostHighlights);

/** Copies a full-resolution tile (RGB half floats, tightly packed) into [dst]; the caller checks bounds. */
void copyTile(const DecodedImage& img, int x, int y, int w, int h, uint16_t* dst);

/** Noise std in the develop shader's denoise domain (sqrt of decoded linear values); 0 = not measured. */
struct NoiseLevel {
    float luma = 0.0f;
    float chroma = 0.0f;
};

/** Measures luma and colour noise on the full-resolution image (noise.cpp); zeros for display sources. */
NoiseLevel estimateNoise(const DecodedImage& img);

// Applies a TIFF/EXIF orientation (1..8) in place.
void applyOrientation(DecodedImage& image, int tiffOrientation);

/**
 * Where source pixel (sx, sy) of a [w] x [h] image lands after TIFF/EXIF orientation [o], as an index into
 * the oriented image's RGB samples. For one source row it's linear in sx: start(sy) + sx * step.
 */
struct OrientedRow {
    size_t start;
    ptrdiff_t step;
};
OrientedRow orientedRow(int o, int w, int h, int sy);

}  // namespace rawboy

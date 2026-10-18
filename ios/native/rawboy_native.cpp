// RawBoy iOS: the decoder's C API (rawboy_native.h), the iOS counterpart of jni_bridge.cpp.

#include "rawboy_native.h"

#include "blemish.h"
#include "rawboy.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <new>
#include <string>

using rawboy::DecodedImage;

struct RBImage {
    DecodedImage image;
};

namespace {

void setError(char* error, size_t size, const std::string& message) {
    if (error && size) std::snprintf(error, size, "%s", message.c_str());
}

}  // namespace

extern "C" {

bool rb_has_dng_sdk(void) { return RAWBOY_WITH_DNG_SDK != 0; }

RBImage* rb_open(const uint8_t* data, size_t size, float extraBlack, char* error, size_t errorSize) {
    if (!data || size == 0) {
        setError(error, errorSize, "file is empty");
        return nullptr;
    }
    try {
        auto handle = std::make_unique<RBImage>();
        std::string reason;
        if (!rawboy::decodeAny(data, size, handle->image, reason, extraBlack)) {
            setError(error, errorSize, "Cannot decode file: " + reason);
            return nullptr;
        }
        return handle.release();
    } catch (const std::bad_alloc&) {
        setError(error, errorSize, "Not enough memory to decode this file");
    } catch (const std::exception& e) {
        setError(error, errorSize, e.what());
    }
    return nullptr;
}

RBImage* rb_open_pixels(const uint16_t* rgba, int width, int height, size_t rowBytes, int orientation,
                        const uint8_t* file, size_t fileSize, char* error, size_t errorSize) {
    try {
        auto handle = std::make_unique<RBImage>();
        std::string reason;
        if (!rawboy::decodeDisplayPixels(rgba, rawboy::DisplayPixels::LinearHalf, width, height, rowBytes,
                                         orientation, file, fileSize, handle->image, reason)) {
            setError(error, errorSize, "Cannot decode file: " + reason);
            return nullptr;
        }
        return handle.release();
    } catch (const std::bad_alloc&) {
        setError(error, errorSize, "Not enough memory to decode this file");
    } catch (const std::exception& e) {
        setError(error, errorSize, e.what());
    }
    return nullptr;
}

void rb_close(RBImage* image) { delete image; }
bool rb_display_referred(const RBImage* image) { return image->image.displayReferred; }

int rb_width(const RBImage* image) { return image->image.width; }
int rb_height(const RBImage* image) { return image->image.height; }

void rb_as_shot_white(const RBImage* image, double* out) {
    out[0] = image->image.whiteX;
    out[1] = image->image.whiteY;
}

float rb_iso(const RBImage* image) { return image->image.iso; }
float rb_baseline_exposure(const RBImage* image) { return image->image.baselineExposure; }
const char* rb_camera(const RBImage* image) { return image->image.camera.c_str(); }
const char* rb_decoder(const RBImage* image) { return image->image.decoder.c_str(); }
const char* rb_lens_name(const RBImage* image) { return image->image.lensName.c_str(); }

void rb_metadata(const RBImage* image, RBMetadata* out) {
    const rawboy::PhotoMetadata& m = image->image.metadata;
    out->make = m.make.c_str();
    out->model = m.model.c_str();
    out->lensMake = m.lensMake.c_str();
    out->lensModel = m.lensModel.c_str();
    out->artist = m.artist.c_str();
    out->copyright = m.copyright.c_str();
    out->dateTimeOriginal = m.dateTimeOriginal.c_str();
    out->subSecTimeOriginal = m.subSecTimeOriginal.c_str();
    out->offsetTimeOriginal = m.offsetTimeOriginal.c_str();
    out->exposureTime = m.exposureTime;
    out->fNumber = m.fNumber;
    out->exposureBias = m.exposureBias;
    out->focalLength = m.focalLength;
    out->focalLength35mm = m.focalLength35mm;
    out->iso = m.iso;
    out->hasGps = m.hasGps;
    std::memcpy(out->gpsLatitude, m.gpsLatitude, sizeof m.gpsLatitude);
    std::memcpy(out->gpsLongitude, m.gpsLongitude, sizeof m.gpsLongitude);
    out->gpsLatitudeRef = m.gpsLatitudeRef;
    out->gpsLongitudeRef = m.gpsLongitudeRef;
    out->gpsAltitude = m.gpsAltitude;
    out->gpsBelowSeaLevel = m.gpsBelowSeaLevel;
}

// Same layout as jni_bridge.cpp's lensInfo.
void rb_lens_info(const RBImage* image, double* v) {
    const DecodedImage* img = &image->image;
    const rawboy::LensOpcodes& l = img->lens;
    std::fill(v, v + 41, 0.0);
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
}

const float* rb_lens_shading(const RBImage* image, size_t* count) {
    const rawboy::ShadingGrid& s = image->image.shading;
    *count = s.gains.size();
    return s.gains.empty() ? nullptr : s.gains.data();
}

void rb_downsample(const RBImage* image, int dstW, int dstH, uint16_t* dst, bool boostHighlights) {
    rawboy::downsample(image->image, dstW, dstH, dst, boostHighlights);
}

bool rb_copy_tile(const RBImage* image, int x, int y, int width, int height, uint16_t* dst) {
    const DecodedImage& img = image->image;
    if (x < 0 || y < 0 || width <= 0 || height <= 0 || x + width > img.width || y + height > img.height) return false;
    rawboy::copyTile(img, x, y, width, height, dst);
    return true;
}

void rb_noise_level(const RBImage* image, float* out) {
    const rawboy::NoiseLevel n = rawboy::estimateNoise(image->image);
    out[0] = n.luma;
    out[1] = n.chroma;
}

float* rb_detect_blemishes(const RBImage* image, const float* mask, int maskW, int maskH, float faceDiameter,
                           size_t* count) {
    *count = 0;
    try {
        const auto spots = rawboy::detectBlemishes(image->image, {mask, maskW, maskH}, faceDiameter);
        if (spots.empty()) return nullptr;
        auto* flat = static_cast<float*>(std::malloc(spots.size() * 4 * sizeof(float)));
        if (!flat) return nullptr;
        for (size_t i = 0; i < spots.size(); ++i) {
            flat[i * 4] = spots[i].x;
            flat[i * 4 + 1] = spots[i].y;
            flat[i * 4 + 2] = spots[i].radius;
            flat[i * 4 + 3] = spots[i].score;
        }
        *count = spots.size() * 4;
        return flat;
    } catch (const std::exception&) {
        return nullptr;
    }
}

void rb_free_floats(float* values) { std::free(values); }

void rb_heal_blemishes(RBImage* image, const float* spots, size_t count) {
    DecodedImage& img = image->image;
    try {
        rawboy::restoreBackups(img, img.healBackups);
        for (size_t i = 0; i + 3 < count; i += 4) {
            rawboy::healBlemish(img, {spots[i], spots[i + 1], spots[i + 2], spots[i + 3]}, img.healBackups);
        }
    } catch (const std::exception&) {
        // Out of memory for a backup: the spots healed so far stay healed and undoable.
    }
}

}  // extern "C"

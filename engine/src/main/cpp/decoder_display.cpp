// Display-referred photos: JPEG (with Ultra HDR gain maps) and TIFF decoded here, HEIC and PNG decoded by the platform
// and handed in as pixels. All are taken back to the scene-linear light RawBoy's default develop renders them from
// (display_inverse.h), so the rest of the pipeline treats them like any raw.

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "display_inverse.h"
#include "display_profile.h"
#include "exif_reader.h"
#include "jpeg_decode.h"
#include "rawboy.h"

#include <zlib.h>

namespace rawboy {

namespace {

// Gain (HDR headroom) is restored per channel, only where a channel nears the scene value AgX renders as white (the
// last kRampEv below it): a display source is rendered with AgX per channel, so a channel at white stays white however
// far it's pushed, and the default render keeps matching the SDR picture, while Highlights or a lower Exposure reveal
// what was clipped. Weighting by the brightest channel instead brightened skies by 13-24 levels (blue near white, red
// and green boosted with it).
constexpr float kRampEv = 0.5f;

float ramp(float scene) {
    static const float whiteEv = std::log2(DisplayInverse::get().sceneWhite());
    const float t = std::clamp((std::log2(std::max(scene, 1e-6f)) - (whiteEv - kRampEv)) / kRampEv, 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

struct Segment {
    uint8_t marker;
    const uint8_t* data;  // payload after the length field
    size_t size;
};

/** The marker segments before the first scan. */
std::vector<Segment> segments(const uint8_t* d, size_t n) {
    std::vector<Segment> out;
    size_t p = 2;
    while (p + 4 <= n) {
        if (d[p] != 0xFF) break;
        while (p < n && d[p] == 0xFF) ++p;
        if (p >= n) break;
        const uint8_t marker = d[p++];
        if (marker == 0xD8 || (marker >= 0xD0 && marker <= 0xD7) || marker == 0x01) continue;
        if (marker == 0xDA || marker == 0xD9 || p + 2 > n) break;
        const size_t len = size_t(d[p]) << 8 | d[p + 1];
        if (len < 2 || p + len > n) break;
        out.push_back({marker, d + p + 2, len - 2});
        p += len;
    }
    return out;
}

bool startsWith(const Segment& s, const char* prefix, size_t len) {
    return s.size >= len && std::memcmp(s.data, prefix, len) == 0;
}

const char kExif[] = "Exif\0\0";
const char kXmp[] = "http://ns.adobe.com/xap/1.0/";  // + NUL
const char kIcc[] = "ICC_PROFILE";                   // + NUL, sequence number, count
const char kMpf[] = "MPF";                           // + NUL
const char kIso[] = "urn:iso:std:iso:ts:21496:-1";   // + NUL

struct Markers {
    const uint8_t* exif = nullptr;
    size_t exifSize = 0;
    std::string xmp;
    std::vector<uint8_t> icc;
    const uint8_t* mpf = nullptr;  // the MPF TIFF header (offsets in it count from here)
    size_t mpfSize = 0;
    const uint8_t* iso = nullptr;  // ISO 21496-1 gain map metadata
    size_t isoSize = 0;
};

Markers readMarkers(const uint8_t* d, size_t n) {
    Markers m;
    std::vector<std::pair<int, std::vector<uint8_t>>> iccChunks;
    for (const Segment& s : segments(d, n)) {
        if (s.marker == 0xE1 && startsWith(s, kExif, 6)) {
            m.exif = s.data + 6;
            m.exifSize = s.size - 6;
        } else if (s.marker == 0xE1 && startsWith(s, kXmp, sizeof kXmp)) {
            m.xmp.assign(reinterpret_cast<const char*>(s.data + sizeof kXmp), s.size - sizeof kXmp);
        } else if (s.marker == 0xE2 && startsWith(s, kIcc, sizeof kIcc) && s.size > sizeof kIcc + 2) {
            iccChunks.push_back({s.data[sizeof kIcc], {s.data + sizeof kIcc + 2, s.data + s.size}});
        } else if (s.marker == 0xE2 && startsWith(s, kMpf, sizeof kMpf)) {
            m.mpf = s.data + sizeof kMpf;
            m.mpfSize = s.size - sizeof kMpf;
        } else if (s.marker == 0xE2 && startsWith(s, kIso, sizeof kIso)) {
            m.iso = s.data + sizeof kIso;
            m.isoSize = s.size - sizeof kIso;
        }
    }
    std::sort(iccChunks.begin(), iccChunks.end(), [](const auto& a, const auto& b) { return a.first < b.first; });
    for (auto& c : iccChunks) m.icc.insert(m.icc.end(), c.second.begin(), c.second.end());
    return m;
}

/**
 * The second image of a Multi-Picture Format file (the gain map in Ultra HDR JPEGs): its bytes within the file, or
 * {nullptr, 0}. [fileStart]/[fileSize]: the whole file; MPF offsets count from the MPF header.
 */
std::pair<const uint8_t*, size_t> secondImage(const Markers& m, const uint8_t* fileStart, size_t fileSize) {
    const uint8_t* t = m.mpf;
    const size_t n = m.mpfSize;
    if (!t || n < 8) return {nullptr, 0};
    const bool le = t[0] == 'I';
    auto u16 = [&](size_t o) -> uint32_t { return o + 2 > n ? 0 : le ? t[o] | t[o + 1] << 8 : t[o] << 8 | t[o + 1]; };
    auto u32 = [&](size_t o) -> uint32_t {
        return o + 4 > n ? 0 : le ? uint32_t(t[o]) | t[o + 1] << 8 | t[o + 2] << 16 | uint32_t(t[o + 3]) << 24
                                  : uint32_t(t[o]) << 24 | t[o + 1] << 16 | t[o + 2] << 8 | t[o + 3];
    };
    const size_t ifd = u32(4);
    const uint32_t count = u16(ifd);
    for (uint32_t i = 0; i < count; ++i) {
        const size_t e = ifd + 2 + i * 12;
        if (u16(e) != 0xB002) continue;  // MPEntry: 16 bytes per image
        const uint32_t entries = u32(e + 4) / 16;
        const size_t list = u32(e + 8);
        if (entries < 2) return {nullptr, 0};
        const size_t size = u32(list + 16 + 4), offset = u32(list + 16 + 8);
        const size_t start = static_cast<size_t>(t - fileStart) + offset;
        if (offset == 0 || start + size > fileSize || size < 4) return {nullptr, 0};
        if (fileStart[start] != 0xFF || fileStart[start + 1] != 0xD8) return {nullptr, 0};
        return {fileStart + start, size};
    }
    return {nullptr, 0};
}

/** Gain map metadata (Ultra HDR XMP hdrgm or ISO 21496-1), in log2 units; one value per channel. */
struct HdrGainMapParams {
    float min[3] = {0, 0, 0}, max[3] = {0, 0, 0}, gamma[3] = {1, 1, 1};
    float offsetSdr[3] = {1 / 64.0f, 1 / 64.0f, 1 / 64.0f}, offsetHdr[3] = {1 / 64.0f, 1 / 64.0f, 1 / 64.0f};
};

/** hdrgm:[name] as an attribute (name="v") or element (a number or an rdf:Seq of 3); false if absent. */
bool xmpValue(const std::string& xmp, const std::string& name, float out[3]) {
    const std::string key = "hdrgm:" + name;
    size_t p = 0;
    while ((p = xmp.find(key, p)) != std::string::npos) {
        size_t q = p + key.size();
        while (q < xmp.size() && std::isspace(static_cast<unsigned char>(xmp[q]))) ++q;
        if (q < xmp.size() && xmp[q] == '=') {
            ++q;
            while (q < xmp.size() && std::isspace(static_cast<unsigned char>(xmp[q]))) ++q;
            if (q >= xmp.size() || (xmp[q] != '"' && xmp[q] != '\'')) return false;
            out[0] = out[1] = out[2] = std::strtof(xmp.c_str() + q + 1, nullptr);
            return true;
        }
        if (q < xmp.size() && xmp[q] == '>') {
            const size_t end = xmp.find("</" + key, q);
            if (end == std::string::npos) return false;
            const std::string body = xmp.substr(q + 1, end - q - 1);
            int i = 0;
            size_t li = 0;
            while (i < 3 && (li = body.find("<rdf:li", li)) != std::string::npos) {
                li = body.find('>', li);
                if (li == std::string::npos) break;
                out[i++] = std::strtof(body.c_str() + li + 1, nullptr);
            }
            if (i == 0) {
                out[0] = out[1] = out[2] = std::strtof(body.c_str(), nullptr);
            } else if (i < 3) {
                out[1] = out[2] = out[0];
            }
            return true;
        }
        p = q;
    }
    return false;
}

bool fromXmp(const std::string& xmp, HdrGainMapParams& g) {
    if (xmp.find("hdrgm:GainMapMax") == std::string::npos) return false;
    if (xmp.find("BaseRenditionIsHDR=\"True\"") != std::string::npos) return false;  // an HDR base: not handled
    if (!xmpValue(xmp, "GainMapMax", g.max)) return false;
    xmpValue(xmp, "GainMapMin", g.min);
    xmpValue(xmp, "Gamma", g.gamma);
    xmpValue(xmp, "OffsetSDR", g.offsetSdr);
    xmpValue(xmp, "OffsetHDR", g.offsetHdr);
    return true;
}

/** ISO 21496-1 binary metadata (big-endian fractions), as libultrahdr reads it. */
bool fromIso(const uint8_t* d, size_t n, HdrGainMapParams& g) {
    size_t p = 0;
    bool ok = true;
    auto u8 = [&]() -> uint32_t { if (p + 1 > n) { ok = false; return 0; } return d[p++]; };
    auto u16 = [&]() -> uint32_t { const uint32_t a = u8(); return a << 8 | u8(); };
    auto u32 = [&]() -> uint32_t { const uint32_t a = u16(); return a << 16 | u16(); };
    auto s32 = [&]() -> int32_t { return static_cast<int32_t>(u32()); };
    if (u16() != 0) return false;  // minimum version
    u16();                          // writer version
    if (!ok || p >= n) return false;  // version-only block (the primary image's)
    const uint32_t flags = u8();
    const int channels = (flags & 0x80) ? 3 : 1;
    if (flags & 0x04) return false;  // backward direction: the base is the HDR rendition
    const bool common = flags & 0x08;
    auto frac = [&](bool isSigned, uint32_t den) -> float {
        const double num = isSigned ? double(s32()) : double(u32());
        const double d2 = common ? double(den) : double(u32());
        return d2 != 0 ? float(num / d2) : 0.0f;
    };
    const uint32_t den = common ? u32() : 0;
    if (common) {
        u32();
        u32();  // base / alternate HDR headroom numerators
    } else {
        u32(); u32(); u32(); u32();
    }
    for (int c = 0; c < channels; ++c) {
        g.min[c] = frac(true, den);
        g.max[c] = frac(true, den);
        g.gamma[c] = frac(false, den);
        g.offsetSdr[c] = frac(true, den);
        g.offsetHdr[c] = frac(true, den);
    }
    if (channels == 1)
        for (int c = 1; c < 3; ++c) {
            g.min[c] = g.min[0], g.max[c] = g.max[0], g.gamma[c] = g.gamma[0];
            g.offsetSdr[c] = g.offsetSdr[0], g.offsetHdr[c] = g.offsetHdr[0];
        }
    return ok && g.gamma[0] > 0;
}

/** A decoded gain map: log2 boost per pixel and channel, at its own (usually lower) resolution. */
struct HdrGainMap {
    int width = 0, height = 0;
    std::vector<float> logBoost;  // RGB

    /** Bilinear log2 boost at full-image pixel (x, y) of a [w] x [h] image (unoriented). */
    void sample(int x, int y, int w, int h, float out[3]) const {
        const float gx = std::clamp((x + 0.5f) * width / w - 0.5f, 0.0f, float(width - 1));
        const float gy = std::clamp((y + 0.5f) * height / h - 0.5f, 0.0f, float(height - 1));
        const int x0 = int(gx), y0 = int(gy);
        const int x1 = std::min(x0 + 1, width - 1), y1 = std::min(y0 + 1, height - 1);
        const float fx = gx - x0, fy = gy - y0;
        const float* a = &logBoost[(size_t(y0) * width + x0) * 3];
        const float* b = &logBoost[(size_t(y0) * width + x1) * 3];
        const float* c = &logBoost[(size_t(y1) * width + x0) * 3];
        const float* d = &logBoost[(size_t(y1) * width + x1) * 3];
        for (int i = 0; i < 3; ++i) {
            out[i] = (a[i] * (1 - fx) + b[i] * fx) * (1 - fy) + (c[i] * (1 - fx) + d[i] * fx) * fy;
        }
    }
};

bool decodeHdrGainMap(const uint8_t* data, size_t size, HdrGainMap& out, std::string& note) {
    const Markers m = readMarkers(data, size);
    HdrGainMapParams g;
    if (!fromXmp(m.xmp, g) && !(m.iso && fromIso(m.iso, m.isoSize, g))) {
        note = "gain map metadata not understood";
        return false;
    }
    struct Ctx {
        HdrGainMap* map;
        const HdrGainMapParams* g;
    } ctx{&out, &g};
    RBJpegSink sink{};
    sink.context = &ctx;
    sink.begin = [](void* c, int w, int h) {
        auto* x = static_cast<Ctx*>(c);
        x->map->width = w;
        x->map->height = h;
        x->map->logBoost.resize(size_t(w) * h * 3);
        return 1;
    };
    sink.rows = [](void* c, int y, int rows, const uint16_t* rgb) {
        auto* x = static_cast<Ctx*>(c);
        const HdrGainMapParams& g = *x->g;
        float* o = x->map->logBoost.data() + size_t(y) * x->map->width * 3;
        for (size_t i = 0, n = size_t(rows) * x->map->width * 3; i < n; ++i) {
            const int ch = int(i % 3);
            const float recovery = std::pow(rgb[i] / 65535.0f, 1.0f / g.gamma[ch]);
            o[i] = g.min[ch] + (g.max[ch] - g.min[ch]) * recovery;
        }
        return 1;
    };
    char error[256] = {};
    if (!rb_jpeg_decode(data, size, &sink, error, sizeof error)) {
        note = error;
        return false;
    }
    return true;
}

/** Fills [out]'s non-pixel fields shared by both display paths. */
void describe(DecodedImage& out, const PhotoMetadata& meta, const std::string& decoder) {
    out.metadata = meta;
    out.displayReferred = true;
    out.decoder = decoder;
    out.camera = meta.make.empty() ? meta.model : meta.make + " " + meta.model;
    out.lensName = meta.lensModel;
    out.iso = meta.iso;
    // Already white balanced by the camera: D65 is "as shot", and Temperature/Tint move relative to it.
    out.whiteX = 0.3127;
    out.whiteY = 0.3290;
}

/** scene = inverse(display) x HDR boost near white; [boost] is log2 per channel (null: none). */
inline void toScene(const float display709[3], const float* boost, uint16_t* o) {
    float s[3];
    DisplayInverse::get().toScene(display709, s);
    for (int c = 0; c < 3; ++c) {
        const float w = boost ? ramp(s[c]) : 0.0f;
        const float k = w > 0 ? std::exp2(std::max(boost[c], 0.0f) * w) : 1.0f;
        o[c] = floatToHalf(s[c] * k);
    }
}

}  // namespace

bool looksLikeJpeg(const uint8_t* d, size_t n) { return n > 3 && d[0] == 0xFF && d[1] == 0xD8 && d[2] == 0xFF; }

bool decodeJpeg(const uint8_t* data, size_t size, DecodedImage& out, std::string& error) {
    const Markers markers = readMarkers(data, size);
    int orientation = 1;
    PhotoMetadata meta;
    if (markers.exif) readExif(markers.exif, markers.exifSize, meta, orientation);
    const DisplayProfile profile =
        markers.icc.empty() ? DisplayProfile::srgb() : DisplayProfile::fromIcc(markers.icc.data(), markers.icc.size());

    HdrGainMap gain;
    std::string gainNote;
    const auto [gainData, gainSize] = secondImage(markers, data, size);
    const bool hasGain = gainData && decodeHdrGainMap(gainData, gainSize, gain, gainNote);

    struct Ctx {
        DecodedImage* out;
        const DisplayProfile* profile;
        const HdrGainMap* gain;
        int orientation;
        int width = 0, height = 0;
    } ctx{&out, &profile, hasGain ? &gain : nullptr, orientation};

    RBJpegSink sink{};
    sink.context = &ctx;
    sink.begin = [](void* c, int w, int h) {
        auto* x = static_cast<Ctx*>(c);
        x->width = w;
        x->height = h;
        try {
            x->out->rgb.assign(size_t(w) * h * 3, 0);
        } catch (const std::bad_alloc&) {
            return 0;
        }
        return 1;
    };
    sink.rows = [](void* c, int y, int rows, const uint16_t* rgb) {
        auto* x = static_cast<Ctx*>(c);
        const int w = x->width, h = x->height;
        parallelRows(rows, [&](int r0, int r1) {
            for (int r = r0; r < r1; ++r) {
                const int sy = y + r;
                const OrientedRow dest = orientedRow(x->orientation, w, h, sy);
                const uint16_t* in = rgb + size_t(r) * w * 3;
                uint16_t* o = x->out->rgb.data() + dest.start;
                for (int sx = 0; sx < w; ++sx, in += 3, o += dest.step) {
                    float lin[3];
                    x->profile->toLinear709(in, lin);
                    float boost[3];
                    if (x->gain) x->gain->sample(sx, sy, w, h, boost);
                    toScene(lin, x->gain ? boost : nullptr, o);
                }
            }
        });
        return 1;
    };
    char message[256] = {};
    if (!rb_jpeg_decode(data, size, &sink, message, sizeof message)) {
        error = message;
        if (ctx.width > 0 && out.rgb.empty()) error = "Not enough memory to decode this JPEG";
        return false;
    }
    const bool swap = orientation >= 5 && orientation <= 8;
    out.width = swap ? ctx.height : ctx.width;
    out.height = swap ? ctx.width : ctx.height;
    out.sensorWidth = ctx.width;
    out.sensorHeight = ctx.height;
    out.orientation = orientation;
    std::string decoder = std::string("jpegli · ") + profile.name;
    if (hasGain) decoder += " · Ultra HDR";
    else if (!gainNote.empty()) decoder += " · gain map skipped: " + gainNote;
    describe(out, meta, decoder);
    return true;
}

bool decodeDisplayPixels(const void* pixels, DisplayPixels format, int width, int height, size_t rowBytes,
                         int orientation, const uint8_t* file, size_t fileSize, DecodedImage& out, std::string& error) {
    // Display P3 -> sRGB primaries, both D65 (linear).
    static constexpr float kP3To709[9] = {1.2249402f, -0.2249402f, 0.0f, -0.0420570f, 1.0420570f, 0.0f,
                                          -0.0196376f, -0.0786361f, 1.0982736f};
    float decode8[256];
    for (int i = 0; i < 256; ++i) decode8[i] = srgbToLinear(i / 255.0f);  // Display P3 uses the sRGB curve too
    PhotoMetadata meta;
    const uint8_t* exif = nullptr;
    size_t exifSize = 0;
    int exifOrientation = 1;
    if (file && (findHeifExif(file, fileSize, exif, exifSize) || findPngExif(file, fileSize, exif, exifSize))) {
        readExif(exif, exifSize, meta, exifOrientation);
    }
    if (orientation < 1 || orientation > 8) orientation = 1;
    try {
        out.rgb.assign(size_t(width) * height * 3, 0);
    } catch (const std::bad_alloc&) {
        error = "Not enough memory to decode this image";
        return false;
    }
    parallelRows(height, [&](int r0, int r1) {
        for (int y = r0; y < r1; ++y) {
            const OrientedRow dest = orientedRow(orientation, width, height, y);
            const uint8_t* row = static_cast<const uint8_t*>(pixels) + size_t(y) * rowBytes;
            uint16_t* o = out.rgb.data() + dest.start;
            for (int x = 0; x < width; ++x, o += dest.step) {
                float lin[3];
                for (int c = 0; c < 3; ++c) {
                    switch (format) {
                        case DisplayPixels::LinearHalf: {
                            uint16_t h;
                            std::memcpy(&h, row + (size_t(x) * 4 + c) * 2, 2);
                            lin[c] = halfToFloat(h);
                            break;
                        }
                        case DisplayPixels::LinearFloat:
                            std::memcpy(&lin[c], row + (size_t(x) * 4 + c) * 4, 4);
                            break;
                        default:
                            lin[c] = decode8[row[size_t(x) * 4 + c]];
                    }
                }
                if (format == DisplayPixels::DisplayP3_8) {
                    const float r = lin[0], g = lin[1], b = lin[2];
                    for (int c = 0; c < 3; ++c) {
                        lin[c] = kP3To709[c * 3] * r + kP3To709[c * 3 + 1] * g + kP3To709[c * 3 + 2] * b;
                    }
                }
                toScene(lin, nullptr, o);
            }
        }
    });
    const bool swap = orientation >= 5 && orientation <= 8;
    out.width = swap ? height : width;
    out.height = swap ? width : height;
    out.sensorWidth = width;
    out.sensorHeight = height;
    out.orientation = orientation;
    describe(out, meta, "platform decoder");
    return true;
}

namespace {

/** Little/big-endian reader over a TIFF file's IFD0. */
class TiffFile {
public:
    TiffFile(const uint8_t* d, size_t n) : d_(d), n_(n), le_(n >= 2 && d[0] == 'I') {}

    uint16_t u16(size_t o) const {
        if (o + 2 > n_) return 0;
        return le_ ? uint16_t(d_[o] | d_[o + 1] << 8) : uint16_t(d_[o] << 8 | d_[o + 1]);
    }
    uint32_t u32(size_t o) const {
        if (o + 4 > n_) return 0;
        return le_ ? uint32_t(d_[o]) | uint32_t(d_[o + 1]) << 8 | uint32_t(d_[o + 2]) << 16 | uint32_t(d_[o + 3]) << 24
                   : uint32_t(d_[o]) << 24 | uint32_t(d_[o + 1]) << 16 | uint32_t(d_[o + 2]) << 8 | uint32_t(d_[o + 3]);
    }
    bool littleEndian() const { return le_; }

    /** Values of IFD0's [tag] (SHORT or LONG; BYTE/UNDEFINED as bytes); empty if absent. */
    std::vector<uint32_t> values(uint16_t tag) const {
        const size_t ifd = u32(4);
        const int count = u16(ifd);
        for (int i = 0; i < count; ++i) {
            const size_t e = ifd + 2 + size_t(i) * 12;
            if (e + 12 > n_) break;
            if (u16(e) != tag) continue;
            const uint16_t type = u16(e + 2);
            const uint32_t n = u32(e + 4);
            const int size = type == 3 ? 2 : type == 4 ? 4 : (type == 1 || type == 7) ? 1 : 0;
            if (size == 0 || n > (1u << 26)) return {};
            const size_t at = size_t(size) * n <= 4 ? e + 8 : u32(e + 8);
            if (at + size_t(size) * n > n_) return {};
            std::vector<uint32_t> v(n);
            for (uint32_t k = 0; k < n; ++k) {
                v[k] = size == 2 ? u16(at + k * 2) : size == 4 ? u32(at + k * 4) : d_[at + k];
            }
            return v;
        }
        return {};
    }
    uint32_t value(uint16_t tag, uint32_t fallback) const {
        const auto v = values(tag);
        return v.empty() ? fallback : v[0];
    }

private:
    const uint8_t* d_;
    size_t n_;
    bool le_;
};

/** TIFF LZW (MSB-first codes, "early change"). */
bool lzwDecode(const uint8_t* in, size_t n, std::vector<uint8_t>& out) {
    std::vector<uint16_t> prefix(4096);
    std::vector<uint8_t> suffix(4096), first(4096);
    std::vector<uint8_t> stack(4096);
    for (int i = 0; i < 256; ++i) suffix[i] = first[i] = uint8_t(i);
    int width = 9, next = 258, old = -1;
    uint64_t bits = 0;
    int have = 0;
    size_t p = 0;
    auto emit = [&](int code) {
        int len = 0;
        for (int c = code; len < 4096; c = prefix[c]) {
            stack[len++] = suffix[c];
            if (c < 256) break;
        }
        while (len) out.push_back(stack[--len]);
    };
    for (;;) {
        while (have < width && p < n) bits = bits << 8 | in[p++], have += 8;
        if (have < width) break;
        const int code = int(bits >> (have - width)) & ((1 << width) - 1);
        have -= width;
        if (code == 257) break;  // end of information
        if (code == 256) {       // clear
            width = 9, next = 258, old = -1;
            continue;
        }
        if (old < 0) {
            if (code > 255) return false;
            emit(code);
            old = code;
            continue;
        }
        if (code < next) {
            emit(code);
            if (next < 4096) {
                prefix[next] = uint16_t(old), suffix[next] = first[code], first[next] = first[old];
                ++next;
            }
        } else if (code == next && next < 4096) {
            prefix[next] = uint16_t(old), suffix[next] = first[old], first[next] = first[old], ++next;
            emit(code);
        } else {
            return false;
        }
        old = code;
        if (next == (1 << width) - 1 && width < 12) ++width;
    }
    return true;
}

bool packBitsDecode(const uint8_t* in, size_t n, std::vector<uint8_t>& out) {
    size_t p = 0;
    while (p < n) {
        const int8_t h = int8_t(in[p++]);
        if (h >= 0) {
            if (p + h + 1 > n) return false;
            out.insert(out.end(), in + p, in + p + h + 1);
            p += h + 1;
        } else if (h != -128) {
            if (p >= n) return false;
            out.insert(out.end(), size_t(1 - h), in[p++]);
        }
    }
    return true;
}

bool inflateAll(const uint8_t* in, size_t n, std::vector<uint8_t>& out, size_t expected) {
    out.resize(expected);
    uLongf len = static_cast<uLongf>(expected);
    const int r = uncompress(out.data(), &len, in, static_cast<uLong>(n));
    if (r != Z_OK && r != Z_BUF_ERROR) return false;  // a short or padded stream still fills what it has
    out.resize(len);
    return true;
}

}  // namespace

bool decodeTiff(const uint8_t* data, size_t size, DecodedImage& out, std::string& error) {
    auto fail = [&](const std::string& message) {
        error = message;
        return false;
    };
    const TiffFile t(data, size);
    const int w = int(t.value(256, 0)), h = int(t.value(257, 0));
    const int spp = int(t.value(277, 1));
    const int bps = int(t.value(258, 8));
    const int compression = int(t.value(259, 1));
    const int photometric = int(t.value(262, 2));
    const int predictor = int(t.value(317, 1));
    if (w <= 0 || h <= 0) return fail("not an image TIFF");
    if ((bps != 8 && bps != 16) || int(t.value(339, 1)) != 1) {
        return fail("only 8- and 16-bit integer TIFFs are supported");
    }
    if (spp < 1 || (photometric == 2 && spp < 3) || (photometric != 2 && photometric > 1)) {
        return fail("only RGB and grayscale TIFFs are supported");
    }
    if (spp > 1 && t.value(284, 1) != 1) return fail("planar TIFFs are not supported");
    if (compression != 1 && compression != 5 && compression != 8 && compression != 32946 && compression != 32773) {
        return fail("TIFF compression " + std::to_string(compression) + " is not supported");
    }
    const bool tiled = !t.values(322).empty();
    const int chunkW = tiled ? int(t.value(322, 0)) : w;
    const int chunkH = tiled ? int(t.value(323, 0)) : std::min(h, int(t.value(278, uint32_t(h))));
    const auto offsets = t.values(tiled ? 324 : 273), counts = t.values(tiled ? 325 : 279);
    const int across = (w + chunkW - 1) / std::max(chunkW, 1), down = (h + chunkH - 1) / std::max(chunkH, 1);
    if (chunkW <= 0 || chunkH <= 0 || offsets.size() < size_t(across) * down || counts.size() < offsets.size()) {
        return fail("TIFF strips or tiles missing");
    }

    int orientation = 1;
    PhotoMetadata meta;
    readExif(data, size, meta, orientation);  // IFD0 (orientation, make) and its Exif IFD
    const auto icc = t.values(34675);
    std::vector<uint8_t> iccBytes(icc.begin(), icc.end());
    const DisplayProfile profile =
        iccBytes.empty() ? DisplayProfile::srgb() : DisplayProfile::fromIcc(iccBytes.data(), iccBytes.size());

    try {
        out.rgb.assign(size_t(w) * h * 3, 0);
    } catch (const std::bad_alloc&) {
        return fail("Not enough memory to decode this TIFF");
    }
    const size_t bytesPerSample = size_t(bps / 8);
    const size_t chunkRowBytes = size_t(chunkW) * spp * bytesPerSample;
    // One band of chunk rows at a time (whole tile rows, or strips up to ~64 rows), as coded 16-bit RGB, then
    // converted in parallel.
    const int stripsPerBand = tiled ? 1 : std::max(1, 64 / chunkH);
    std::vector<uint16_t> band(size_t(w) * chunkH * stripsPerBand * 3);
    std::vector<uint8_t> chunk;
    for (int row = 0; row < down; row += stripsPerBand) {
        const int y0 = row * chunkH;
        const int bandRows = std::min(h - y0, chunkH * stripsPerBand);
        for (int r = row; r < std::min(down, row + stripsPerBand); ++r) {
            for (int c = 0; c < across; ++c) {
                const size_t i = size_t(r) * across + c;
                if (size_t(offsets[i]) + counts[i] > size) return fail("TIFF data truncated");
                const uint8_t* src = data + offsets[i];
                const size_t expected = chunkRowBytes * chunkH;
                chunk.clear();
                bool ok = true;
                switch (compression) {
                    case 1: chunk.assign(src, src + std::min<size_t>(counts[i], expected)); break;
                    case 5: ok = lzwDecode(src, counts[i], chunk); break;
                    case 32773: ok = packBitsDecode(src, counts[i], chunk); break;
                    default: ok = inflateAll(src, counts[i], chunk, expected);
                }
                if (!ok) return fail("TIFF data corrupt");
                chunk.resize(expected, 0);
                for (int cy = 0; cy < chunkH; ++cy) {
                    uint8_t* line = chunk.data() + size_t(cy) * chunkRowBytes;
                    if (predictor == 2) {  // horizontal differencing
                        if (bps == 8) {
                            for (size_t k = spp; k < size_t(chunkW) * spp; ++k) {
                                line[k] = uint8_t(line[k] + line[k - spp]);
                            }
                        } else {
                            auto get = [&](size_t k) {
                                return t.littleEndian() ? uint16_t(line[k * 2] | line[k * 2 + 1] << 8)
                                                        : uint16_t(line[k * 2] << 8 | line[k * 2 + 1]);
                            };
                            auto put = [&](size_t k, uint16_t v) {
                                line[k * 2 + (t.littleEndian() ? 0 : 1)] = uint8_t(v);
                                line[k * 2 + (t.littleEndian() ? 1 : 0)] = uint8_t(v >> 8);
                            };
                            for (size_t k = spp; k < size_t(chunkW) * spp; ++k) put(k, uint16_t(get(k) + get(k - spp)));
                        }
                    }
                    const int by = (r - row) * chunkH + cy;
                    if (by >= bandRows) break;
                    for (int cx = 0; cx < chunkW; ++cx) {
                        const int x = c * chunkW + cx;
                        if (x >= w) break;
                        uint16_t px[3];
                        for (int k = 0; k < 3; ++k) {
                            const size_t s = size_t(cx) * spp + (spp >= 3 ? k : 0);
                            uint16_t v = bps == 8 ? uint16_t(line[s] * 257)
                                                  : t.littleEndian() ? uint16_t(line[s * 2] | line[s * 2 + 1] << 8)
                                                                     : uint16_t(line[s * 2] << 8 | line[s * 2 + 1]);
                            if (photometric == 0) v = uint16_t(65535 - v);  // WhiteIsZero
                            px[k] = v;
                        }
                        std::memcpy(&band[(size_t(by) * w + x) * 3], px, sizeof px);
                    }
                }
            }
        }
        parallelRows(bandRows, [&](int r0, int r1) {
            for (int by = r0; by < r1; ++by) {
                const OrientedRow dest = orientedRow(orientation, w, h, y0 + by);
                const uint16_t* in = &band[size_t(by) * w * 3];
                uint16_t* o = out.rgb.data() + dest.start;
                for (int x = 0; x < w; ++x, in += 3, o += dest.step) {
                    float lin[3];
                    profile.toLinear709(in, lin);
                    toScene(lin, nullptr, o);
                }
            }
        });
    }
    const bool swap = orientation >= 5 && orientation <= 8;
    out.width = swap ? h : w;
    out.height = swap ? w : h;
    out.sensorWidth = w;
    out.sensorHeight = h;
    out.orientation = orientation;
    describe(out, meta, std::string("TIFF · ") + profile.name);
    return true;
}

}  // namespace rawboy

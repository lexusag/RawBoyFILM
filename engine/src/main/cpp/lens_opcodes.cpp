#include "lens.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace rawboy {
namespace {

class Reader {
public:
    Reader(const uint8_t* data, size_t size, bool bigEndian) : d_(data), n_(size), be_(bigEndian) {}

    bool ok(size_t off, size_t len) const { return off <= n_ && len <= n_ - off; }

    uint16_t u16(size_t o) const {
        return be_ ? static_cast<uint16_t>(d_[o] << 8 | d_[o + 1]) : static_cast<uint16_t>(d_[o + 1] << 8 | d_[o]);
    }
    uint32_t u32(size_t o) const {
        return be_ ? (uint32_t(d_[o]) << 24 | uint32_t(d_[o + 1]) << 16 | uint32_t(d_[o + 2]) << 8 | d_[o + 3])
                   : (uint32_t(d_[o + 3]) << 24 | uint32_t(d_[o + 2]) << 16 | uint32_t(d_[o + 1]) << 8 | d_[o]);
    }
    double f64(size_t o) const {
        uint64_t v = 0;
        for (int i = 0; i < 8; ++i) v = (v << 8) | d_[be_ ? o + i : o + 7 - i];
        double r;
        std::memcpy(&r, &v, sizeof r);
        return r;
    }
    float f32(size_t o) const {
        uint32_t v = u32(o);
        float r;
        std::memcpy(&r, &v, sizeof r);
        return r;
    }

private:
    const uint8_t* d_;
    size_t n_;
    bool be_;
};

struct Ifd {
    uint32_t subfileType = 0xffffffff;
    uint32_t op2Offset = 0, op2Size = 0, op3Offset = 0, op3Size = 0;
};

constexpr uint16_t kNewSubfileType = 254;
constexpr uint16_t kSubIfds = 330;
constexpr uint16_t kOpcodeList2 = 51009;
constexpr uint16_t kOpcodeList3 = 51022;

void walkIfd(const Reader& r, uint32_t offset, int depth, std::vector<Ifd>& out) {
    while (offset != 0 && depth < 4 && out.size() < 32 && r.ok(offset, 2)) {
        const uint16_t count = r.u16(offset);
        if (!r.ok(offset + 2, count * 12u + 4)) return;
        Ifd ifd;
        std::vector<uint32_t> subIfds;
        for (uint16_t i = 0; i < count; ++i) {
            const size_t e = offset + 2 + i * 12u;
            const uint16_t tag = r.u16(e);
            const uint16_t type = r.u16(e + 2);
            const uint32_t n = r.u32(e + 4);
            if (tag == kNewSubfileType) {
                ifd.subfileType = r.u32(e + 8);
            } else if (tag == kOpcodeList2 || tag == kOpcodeList3) {
                const uint32_t size = n;  // type UNDEFINED, 1 byte each
                const uint32_t at = size <= 4 ? static_cast<uint32_t>(e + 8) : r.u32(e + 8);
                (tag == kOpcodeList2 ? ifd.op2Offset : ifd.op3Offset) = at;
                (tag == kOpcodeList2 ? ifd.op2Size : ifd.op3Size) = size;
            } else if (tag == kSubIfds && (type == 4 || type == 13)) {
                if (n == 1) {
                    subIfds.push_back(r.u32(e + 8));
                } else if (r.ok(r.u32(e + 8), n * 4ull)) {
                    for (uint32_t k = 0; k < n && k < 8; ++k) subIfds.push_back(r.u32(r.u32(e + 8) + k * 4));
                }
            }
        }
        out.push_back(ifd);
        for (uint32_t sub : subIfds) walkIfd(r, sub, depth + 1, out);
        offset = r.u32(offset + 2 + count * 12u);
    }
}

// Opcode lists are always big-endian, regardless of the TIFF byte order.
void parseOpcodeList(const uint8_t* data, size_t size, uint32_t offset, uint32_t length, int list, LensOpcodes& out) {
    if (length < 4 || offset > size || length > size - offset) return;
    const Reader r(data + offset, length, true);
    const uint32_t count = r.u32(0);
    size_t o = 4;
    for (uint32_t i = 0; i < count && r.ok(o, 16); ++i) {
        const uint32_t id = r.u32(o);
        const uint32_t bytes = r.u32(o + 12);
        const size_t p = o + 16;
        if (!r.ok(p, bytes)) return;
        if (id == 9 && bytes >= 76) {  // GainMap
            GainMap g;
            g.top = r.u32(p); g.left = r.u32(p + 4); g.bottom = r.u32(p + 8); g.right = r.u32(p + 12);
            g.plane = r.u32(p + 16); g.planes = r.u32(p + 20);
            g.rowPitch = r.u32(p + 24); g.colPitch = r.u32(p + 28);
            g.pointsV = r.u32(p + 32); g.pointsH = r.u32(p + 36);
            g.spacingV = r.f64(p + 40); g.spacingH = r.f64(p + 48);
            g.originV = r.f64(p + 56); g.originH = r.f64(p + 64);
            g.mapPlanes = r.u32(p + 72);
            g.list = list;
            const uint64_t n = uint64_t(g.pointsV) * g.pointsH * g.mapPlanes;
            if (g.pointsV >= 1 && g.pointsH >= 1 && g.mapPlanes >= 1 && n < (1u << 22) && 76 + n * 4 <= bytes &&
                g.spacingV > 0 && g.spacingH > 0) {
                g.gains.resize(n);
                for (uint64_t k = 0; k < n; ++k) g.gains[k] = r.f32(p + 76 + k * 4);
                out.gainMaps.push_back(std::move(g));
            }
        } else if (id == 1 && bytes >= 4) {  // WarpRectilinear
            WarpRectilinear w;
            w.planes = r.u32(p);
            if (w.planes >= 1 && w.planes <= 3 && bytes >= 4 + w.planes * 48 + 16) {
                for (uint32_t pl = 0; pl < w.planes; ++pl)
                    for (int k = 0; k < 6; ++k) w.coef[pl][k] = r.f64(p + 4 + pl * 48 + k * 8);
                w.centerX = r.f64(p + 4 + w.planes * 48);
                w.centerY = r.f64(p + 4 + w.planes * 48 + 8);
                out.warp = w;
                out.hasWarp = true;
            }
        } else if (id == 3 && bytes >= 56) {  // FixVignetteRadial
            VignetteRadial v;
            for (int k = 0; k < 5; ++k) v.k[k] = r.f64(p + k * 8);
            v.centerX = r.f64(p + 40);
            v.centerY = r.f64(p + 48);
            out.vignette = v;
            out.hasVignette = true;
        }
        o = p + bytes;
    }
}

}  // namespace

float GainMap::at(double rowFract, double colFract, uint32_t mapPlane) const {
    const double fv = std::clamp((rowFract - originV) / spacingV, 0.0, double(pointsV - 1));
    const double fh = std::clamp((colFract - originH) / spacingH, 0.0, double(pointsH - 1));
    const uint32_t v0 = std::min(static_cast<uint32_t>(fv), pointsV > 1 ? pointsV - 2 : 0);
    const uint32_t h0 = std::min(static_cast<uint32_t>(fh), pointsH > 1 ? pointsH - 2 : 0);
    const uint32_t v1 = std::min(v0 + 1, pointsV - 1);
    const uint32_t h1 = std::min(h0 + 1, pointsH - 1);
    const float tv = static_cast<float>(fv - v0);
    const float th = static_cast<float>(fh - h0);
    const uint32_t mp = std::min(mapPlane, mapPlanes - 1);
    auto g = [&](uint32_t v, uint32_t h) { return gains[(size_t(v) * pointsH + h) * mapPlanes + mp]; };
    const float top = g(v0, h0) + (g(v0, h1) - g(v0, h0)) * th;
    const float bottom = g(v1, h0) + (g(v1, h1) - g(v1, h0)) * th;
    return top + (bottom - top) * tv;
}

bool parseDngOpcodes(const uint8_t* data, size_t size, LensOpcodes& out) {
    if (size < 8) return false;
    bool bigEndian;
    if (data[0] == 'I' && data[1] == 'I') bigEndian = false;
    else if (data[0] == 'M' && data[1] == 'M') bigEndian = true;
    else return false;
    const Reader r(data, size, bigEndian);
    if (r.u16(2) != 42) return false;

    std::vector<Ifd> ifds;
    walkIfd(r, r.u32(4), 0, ifds);
    // The main raw image is the IFD with NewSubfileType 0; previews are 1.
    const Ifd* raw = nullptr;
    for (const Ifd& ifd : ifds) {
        if (ifd.subfileType == 0 && (ifd.op2Size || ifd.op3Size)) { raw = &ifd; break; }
    }
    if (!raw) return false;
    if (raw->op2Size) parseOpcodeList(data, size, raw->op2Offset, raw->op2Size, 2, out);
    if (raw->op3Size) parseOpcodeList(data, size, raw->op3Offset, raw->op3Size, 3, out);
    return !out.gainMaps.empty() || out.hasWarp || out.hasVignette;
}

}  // namespace rawboy

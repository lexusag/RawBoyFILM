#include "display_profile.h"

#include <algorithm>
#include <cmath>
#include <functional>
#include <string>

#include "color_math.h"
#include "display_inverse.h"

namespace rawboy {

namespace {

constexpr Mat3 kXyzToRec709 = {{{3.2404542, -1.5371385, -0.4985314},
                                {-0.9692660, 1.8760108, 0.0415560},
                                {0.0556434, -0.2040259, 1.0572252}}};
const Vec3 kD50 = {0.9642, 1.0, 0.8249};
const Vec3 kD65 = {0.95047, 1.0, 1.08883};

std::vector<float> table(const std::function<float(float)>& f) {
    std::vector<float> t(65536);
    for (int i = 0; i < 65536; ++i) t[i] = f(static_cast<float>(i) / 65535.0f);
    return t;
}

class Icc {
public:
    Icc(const uint8_t* d, size_t n) : d_(d), n_(n) {}
    uint32_t u32(size_t o) const {
        if (o + 4 > n_) return 0;
        return uint32_t(d_[o]) << 24 | uint32_t(d_[o + 1]) << 16 | uint32_t(d_[o + 2]) << 8 | d_[o + 3];
    }
    uint16_t u16(size_t o) const { return o + 2 <= n_ ? uint16_t(d_[o] << 8 | d_[o + 1]) : 0; }
    double s15(size_t o) const { return static_cast<int32_t>(u32(o)) / 65536.0; }

    /** Offset and size of tag [sig], or {0, 0}. */
    std::pair<size_t, size_t> tag(const char* sig) const {
        if (n_ < 132) return {0, 0};
        const uint32_t want =
            uint32_t(sig[0]) << 24 | uint32_t(sig[1]) << 16 | uint32_t(sig[2]) << 8 | uint32_t(uint8_t(sig[3]));
        const uint32_t count = u32(128);
        for (uint32_t i = 0; i < count && 132 + i * 12 + 12 <= n_; ++i) {
            const size_t e = 132 + i * 12;
            if (u32(e) != want) continue;
            const size_t off = u32(e + 4), size = u32(e + 8);
            if (off + size > n_ || size < 8) return {0, 0};
            return {off, size};
        }
        return {0, 0};
    }
    bool xyz(const char* sig, Vec3& out) const {
        const auto [o, size] = tag(sig);
        if (size < 20 || u32(o) != 0x58595A20) return false;  // 'XYZ '
        out = {s15(o + 8), s15(o + 12), s15(o + 16)};
        return true;
    }
    /** The transfer curve of tag [sig] ('curv' or 'para'), or an empty function. */
    std::function<float(float)> curve(const char* sig) const {
        const auto [o, size] = tag(sig);
        if (size < 12) return {};
        const uint32_t type = u32(o);
        if (type == 0x63757276) {  // 'curv'
            const uint32_t n = u32(o + 8);
            if (n == 0) return [](float x) { return x; };
            if (n == 1) {
                const float g = u16(o + 12) / 256.0f;
                return [g](float x) { return std::pow(x, g); };
            }
            if (12 + size_t(n) * 2 > size) return {};
            std::vector<float> t(n);
            for (uint32_t i = 0; i < n; ++i) t[i] = u16(o + 12 + i * 2) / 65535.0f;
            return [t](float x) {
                const float p = std::clamp(x, 0.0f, 1.0f) * float(t.size() - 1);
                const size_t i = std::min(size_t(p), t.size() - 2);
                return t[i] + (t[i + 1] - t[i]) * (p - float(i));
            };
        }
        if (type == 0x70617261) {  // 'para': IEC/ICC parametric curve types 0..4
            static const int kCount[] = {1, 3, 4, 5, 7};
            const uint16_t fn = u16(o + 8);
            if (fn > 4 || 12 + size_t(kCount[fn]) * 4 > size) return {};
            double p[7] = {1, 1, 0, 0, 0, 0, 0};  // g a b c d e f
            for (int i = 0; i < kCount[fn]; ++i) p[i] = s15(o + 12 + i * 4);
            const double g = p[0], a = p[1], b = p[2], c = p[3], d = p[4], e = p[5], f = p[6];
            switch (fn) {
                case 0: return [g](float x) { return float(std::pow(double(x), g)); };
                case 1: return [=](float x) { return x >= -b / a ? float(std::pow(a * x + b, g)) : 0.0f; };
                case 2: return [=](float x) { return x >= -b / a ? float(std::pow(a * x + b, g) + c) : float(c); };
                case 3: return [=](float x) { return x >= d ? float(std::pow(a * x + b, g)) : float(c * x); };
                default: return [=](float x) { return x >= d ? float(std::pow(a * x + b, g) + e) : float(c * x + f); };
            }
        }
        return {};
    }

private:
    const uint8_t* d_;
    size_t n_;
};

}  // namespace

DisplayProfile DisplayProfile::srgb() {
    DisplayProfile p;
    const auto t = table(srgbToLinear);
    p.curve[0] = p.curve[1] = p.curve[2] = t;
    return p;
}

DisplayProfile DisplayProfile::fromIcc(const uint8_t* data, size_t size) {
    DisplayProfile fallback = srgb();
    fallback.name = "sRGB (ICC not used)";
    const Icc icc(data, size);
    if (size < 132 || icc.u32(36) != 0x61637370) return fallback;  // 'acsp'
    const uint32_t space = icc.u32(16);
    if (space == 0x47524159) {  // 'GRAY': one curve, sRGB primaries
        const auto k = icc.curve("kTRC");
        if (!k) return fallback;
        DisplayProfile p;
        p.curve[0] = table(k);
        p.curve[1] = p.curve[2] = p.curve[0];
        p.name = "ICC gray";
        return p;
    }
    if (space != 0x52474220) return fallback;  // 'RGB '
    Vec3 r, g, b;
    const auto tr = icc.curve("rTRC"), tg = icc.curve("gTRC"), tb = icc.curve("bTRC");
    if (!icc.xyz("rXYZ", r) || !icc.xyz("gXYZ", g) || !icc.xyz("bXYZ", b) || !tr || !tg || !tb) return fallback;
    // Colorants are adapted to the D50 PCS (Bradford, as profiles do): back to D65, then to Rec.709.
    const Mat3 toXyzD50 = {{{r[0], g[0], b[0]}, {r[1], g[1], b[1]}, {r[2], g[2], b[2]}}};
    const Mat3 m = multiply(kXyzToRec709, multiply(bradfordAdaptation(kD50, kD65), toXyzD50));
    DisplayProfile p;
    p.identity = true;
    for (int i = 0; i < 3; ++i)
        for (int j = 0; j < 3; ++j) {
            p.toRec709[i * 3 + j] = static_cast<float>(m.m[i][j]);
            if (std::fabs(m.m[i][j] - (i == j ? 1.0 : 0.0)) > 0.003) p.identity = false;
        }
    p.curve[0] = table(tr);
    p.curve[1] = table(tg);
    p.curve[2] = table(tb);
    p.name = p.identity ? "ICC sRGB" : "ICC RGB";
    return p;
}

}  // namespace rawboy

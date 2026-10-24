#pragma once

#include <array>
#include <cmath>

namespace rawboy {

using Vec3 = std::array<double, 3>;

struct Mat3 {
    double m[3][3];
};

inline Mat3 multiply(const Mat3& a, const Mat3& b) {
    Mat3 r{};
    for (int i = 0; i < 3; ++i)
        for (int j = 0; j < 3; ++j)
            for (int k = 0; k < 3; ++k) r.m[i][j] += a.m[i][k] * b.m[k][j];
    return r;
}

inline Vec3 multiply(const Mat3& a, const Vec3& v) {
    return {a.m[0][0] * v[0] + a.m[0][1] * v[1] + a.m[0][2] * v[2],
            a.m[1][0] * v[0] + a.m[1][1] * v[1] + a.m[1][2] * v[2],
            a.m[2][0] * v[0] + a.m[2][1] * v[1] + a.m[2][2] * v[2]};
}

inline bool invert(const Mat3& a, Mat3& out) {
    const auto& m = a.m;
    const double det = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
                       m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
                       m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
    if (std::fabs(det) < 1e-12) return false;
    const double inv = 1.0 / det;
    out.m[0][0] = (m[1][1] * m[2][2] - m[1][2] * m[2][1]) * inv;
    out.m[0][1] = (m[0][2] * m[2][1] - m[0][1] * m[2][2]) * inv;
    out.m[0][2] = (m[0][1] * m[1][2] - m[0][2] * m[1][1]) * inv;
    out.m[1][0] = (m[1][2] * m[2][0] - m[1][0] * m[2][2]) * inv;
    out.m[1][1] = (m[0][0] * m[2][2] - m[0][2] * m[2][0]) * inv;
    out.m[1][2] = (m[0][2] * m[1][0] - m[0][0] * m[1][2]) * inv;
    out.m[2][0] = (m[1][0] * m[2][1] - m[1][1] * m[2][0]) * inv;
    out.m[2][1] = (m[0][1] * m[2][0] - m[0][0] * m[2][1]) * inv;
    out.m[2][2] = (m[0][0] * m[1][1] - m[0][1] * m[1][0]) * inv;
    return true;
}

inline Vec3 xyToXYZ(double x, double y) { return {x / y, 1.0, (1.0 - x - y) / y}; }

constexpr Mat3 kXyzToRec2020 = {{{1.7166511880, -0.3556707838, -0.2533662814},
                                 {-0.6666843518, 1.6164812366, 0.0157685458},
                                 {0.0176398574, -0.0427706133, 0.9421031212}}};

constexpr Mat3 kBradford = {{{0.8951, 0.2664, -0.1614},
                             {-0.7502, 1.7135, 0.0367},
                             {0.0389, -0.0685, 1.0296}}};

// Bradford chromatic adaptation from one white (XYZ) to another.
inline Mat3 bradfordAdaptation(const Vec3& srcWhite, const Vec3& dstWhite) {
    Mat3 inv{};
    invert(kBradford, inv);
    const Vec3 s = multiply(kBradford, srcWhite);
    const Vec3 d = multiply(kBradford, dstWhite);
    Mat3 scale{};
    for (int i = 0; i < 3; ++i) scale.m[i][i] = d[i] / s[i];
    return multiply(inv, multiply(scale, kBradford));
}

/**
 * Highlight blending (dcraw's blend_highlights) for one white balanced camera RGB pixel [c], where [clip] holds each
 * channel's value at the sensor's clip. Near clip the pixel keeps its lightness (channel sum) and hue but takes the
 * chroma of a copy clipped at the lowest of [clip], so a blown highlight comes out neutral (at the mean of [clip]).
 * Only pixels with a channel within 10% of its own clip are touched: dcraw blends wherever a channel passes the
 * lowest clip, which with very unequal multipliers (infrared: blue x3.7) took the colour out of bright light that
 * hadn't clipped at all.
 */
inline void blendClipped(float c[3], const float clip[3]) {
    const float r = std::fmax(c[0] / clip[0], std::fmax(c[1] / clip[1], c[2] / clip[2]));
    if (!(r > 0.9f)) return;
    const float u = std::fmin(1.0f, (r - 0.9f) / 0.1f);
    const float t = u * u * (3.0f - 2.0f * u);
    const float lowest = std::fmin(clip[0], std::fmin(clip[1], clip[2]));
    const float k[3] = {std::fmin(c[0], lowest), std::fmin(c[1], lowest), std::fmin(c[2], lowest)};
    // Lightness and two opponent axes (dcraw's trans matrix for three colours).
    const float a0 = 1.7320508f * (c[0] - c[1]), b0 = 2.0f * c[2] - c[0] - c[1];
    const float a1 = 1.7320508f * (k[0] - k[1]), b1 = 2.0f * k[2] - k[0] - k[1];
    const float s0 = a0 * a0 + b0 * b0;
    const float ratio = s0 > 0.0f ? std::sqrt((a1 * a1 + b1 * b1) / s0) : 1.0f;
    const float l = (c[0] + c[1] + c[2]) / 3.0f, a = a0 * ratio / 3.0f, b = b0 * ratio / 3.0f;
    const float blended[3] = {l + 0.8660254f * a - 0.5f * b, l - 0.8660254f * a - 0.5f * b, l + b};
    for (int i = 0; i < 3; ++i) c[i] += (blended[i] - c[i]) * t;
}

}  // namespace rawboy

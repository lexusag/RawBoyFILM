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

}  // namespace rawboy

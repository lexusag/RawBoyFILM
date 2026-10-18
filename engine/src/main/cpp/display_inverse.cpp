#include "display_inverse.h"

#include <algorithm>
#include <cmath>

#include "color_math.h"

namespace rawboy {

namespace {

// --- From shaders/develop.glsl (keep in step). GLSL mat3s are column-major: each vec3 there is a column here.
constexpr float AGX_MIN_EV = -12.47393f;
constexpr float AGX_MAX_EV = 4.026069f;
constexpr float AGX_RANGE = AGX_MAX_EV - AGX_MIN_EV;
// ShaderParams.BASE_EXPOSURE_EV: what Exposure 0 multiplies the decoded image by.
constexpr float BASE_EXPOSURE_EV = 0.7f;
constexpr Mat3 kRec2020ToRec709 = {{{1.6604910021, -0.5876411388, -0.0728498633},
                                    {-0.1245504745, 1.1328998971, -0.0083494226},
                                    {-0.0181507634, -0.1005788980, 1.1187296614}}};

float agxSigmoid(float x) {
    const float x2 = x * x;
    const float x4 = x2 * x2;
    return 15.5f * x4 * x2 - 40.14f * x4 * x + 31.96f * x4 - 6.868f * x2 * x + 0.4298f * x2 + 0.1191f * x - 0.00232f;
}

struct Matrices {
    float to709[9], to2020[9];
};

const Matrices& matrices() {
    static const Matrices m = [] {
        Matrices r{};
        Mat3 inv{};
        invert(kRec2020ToRec709, inv);
        for (int i = 0; i < 3; ++i)
            for (int j = 0; j < 3; ++j) {
                r.to709[i * 3 + j] = static_cast<float>(kRec2020ToRec709.m[i][j]);
                r.to2020[i * 3 + j] = static_cast<float>(inv.m[i][j]);
            }
        return r;
    }();
    return m;
}

inline void apply(const float* m, const float in[3], float out[3]) {
    const float a = in[0], b = in[1], c = in[2];
    out[0] = m[0] * a + m[1] * b + m[2] * c;
    out[1] = m[3] * a + m[4] * b + m[5] * c;
    out[2] = m[6] * a + m[7] * b + m[8] * c;
}

}  // namespace

float srgbToLinear(float v) {
    const float a = std::fabs(v);
    const float l = a <= 0.04045f ? a / 12.92f : std::pow((a + 0.055f) / 1.055f, 2.4f);
    return v < 0 ? -l : l;
}

const DisplayInverse& DisplayInverse::get() {
    static const DisplayInverse instance;
    return instance;
}

DisplayInverse::DisplayInverse() {
    // The sigmoid is monotonic on [0, 1] (generate.py asserts it): invert it by bisection at evenly spaced outputs.
    yMin_ = agxSigmoid(0.0f);
    yMax_ = agxSigmoid(1.0f);
    for (int i = 0; i <= kTableSize; ++i) {
        const float y = yMin_ + (yMax_ - yMin_) * static_cast<float>(i) / kTableSize;
        double lo = 0.0, hi = 1.0;
        for (int k = 0; k < 40; ++k) {
            const double mid = 0.5 * (lo + hi);
            (agxSigmoid(static_cast<float>(mid)) < y ? lo : hi) = mid;
        }
        table_[i] = static_cast<float>(0.5 * (lo + hi));
    }
}

float DisplayInverse::inverseSigmoid(float y) const {
    const float t = std::clamp((y - yMin_) / (yMax_ - yMin_), 0.0f, 1.0f) * kTableSize;
    const int i = std::min(static_cast<int>(t), kTableSize - 1);
    const float f = t - static_cast<float>(i);
    return table_[i] + (table_[i + 1] - table_[i]) * f;
}

void DisplayInverse::toScene(const float display709[3], float scene2020[3]) const {
    const Matrices& m = matrices();
    float r[3];
    apply(m.to2020, display709, r);
    for (float& v : r) v = std::pow(std::max(v, 0.0f), 1.0f / 2.2f);  // linear -> the tone mapper's 2.2 encoding
    for (int i = 0; i < 3; ++i) {
        scene2020[i] = std::exp2(inverseSigmoid(r[i]) * AGX_RANGE + AGX_MIN_EV - BASE_EXPOSURE_EV);
    }
}

float DisplayInverse::sceneWhite() const { return std::exp2(AGX_MAX_EV - BASE_EXPOSURE_EV); }

void DisplayInverse::toDisplay(const float scene2020[3], float display709[3]) const {
    const Matrices& m = matrices();
    const float exposure = std::exp2(BASE_EXPOSURE_EV);
    float s[3] = {scene2020[0] * exposure, scene2020[1] * exposure, scene2020[2] * exposure};
    float c[3], r[3];
    for (int i = 0; i < 3; ++i) {
        const float x = std::clamp((std::log2(std::max(s[i], 1e-10f)) - AGX_MIN_EV) / AGX_RANGE, 0.0f, 1.0f);
        r[i] = std::pow(std::max(agxSigmoid(x), 0.0f), 2.2f);
    }
    apply(m.to709, r, c);
    for (int i = 0; i < 3; ++i) display709[i] = std::clamp(c[i], 0.0f, 1.0f);
}

}  // namespace rawboy

#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace rawboy {

/**
 * How a display-referred file's coded values become display-linear sRGB (Rec.709 primaries, D65): a transfer curve
 * per channel and a 3x3 matrix. From the file's ICC profile (matrix/TRC profiles: sRGB, Display P3, Adobe RGB, ...;
 * gray profiles) or plain sRGB when there is none or it's of another kind (LUT-based).
 */
struct DisplayProfile {
    float toRec709[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};  // row-major, display-linear file RGB -> linear Rec.709
    bool identity = true;                              // toRec709 is the identity (sRGB primaries)
    std::vector<float> curve[3];                       // 65536 entries each: 16-bit coded value -> linear
    const char* name = "sRGB";

    static DisplayProfile srgb();
    /** Parses [icc]; falls back to sRGB (returned with name "sRGB (ICC not used)") if it can't. */
    static DisplayProfile fromIcc(const uint8_t* icc, size_t size);

    void toLinear709(const uint16_t coded[3], float out[3]) const {
        const float r = curve[0][coded[0]], g = curve[1][coded[1]], b = curve[2][coded[2]];
        if (identity) {
            out[0] = r, out[1] = g, out[2] = b;
            return;
        }
        const float* m = toRec709;
        out[0] = m[0] * r + m[1] * g + m[2] * b;
        out[1] = m[3] * r + m[4] * g + m[5] * b;
        out[2] = m[6] * r + m[7] * g + m[8] * b;
    }
};

}  // namespace rawboy

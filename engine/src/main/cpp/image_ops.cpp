#include "rawboy.h"

namespace rawboy {

void downsample(const DecodedImage& img, int dstW, int dstH, uint16_t* dst, bool boostHighlights) {
    const int64_t sw = img.width;
    const int64_t sh = img.height;
    rawboy::parallelRows(dstH, [&](int rowBegin, int rowEnd) {
        for (int y = rowBegin; y < rowEnd; ++y) {
            const int64_t y0 = y * sh / dstH;
            const int64_t y1 = std::max(y0 + 1, (y + 1) * sh / dstH);
            for (int x = 0; x < dstW; ++x) {
                const int64_t x0 = x * sw / dstW;
                const int64_t x1 = std::max(x0 + 1, (x + 1) * sw / dstW);
                float acc[3] = {0, 0, 0};
                for (int64_t sy = y0; sy < y1; ++sy) {
                    const uint16_t* row = img.rgb.data() + (sy * sw + x0) * 3;
                    for (int64_t sx = x0; sx < x1; ++sx, row += 3) {
                        const float r = rawboy::halfToFloat(row[0]);
                        const float g = rawboy::halfToFloat(row[1]);
                        const float b = rawboy::halfToFloat(row[2]);
                        float k = 1.0f;
                        if (boostHighlights) {
                            const float t = std::clamp((std::max({r, g, b}) - 0.6f) / 0.4f, 0.0f, 1.25f);
                            k = 1.0f + 6.0f * t * t;
                        }
                        acc[0] += r * k;
                        acc[1] += g * k;
                        acc[2] += b * k;
                    }
                }
                const float inv = 1.0f / static_cast<float>((y1 - y0) * (x1 - x0));
                uint16_t* out = dst + (static_cast<size_t>(y) * dstW + x) * 3;
                out[0] = rawboy::floatToHalf(acc[0] * inv);
                out[1] = rawboy::floatToHalf(acc[1] * inv);
                out[2] = rawboy::floatToHalf(acc[2] * inv);
            }
        }
    });
}

void copyTile(const DecodedImage& img, int x, int y, int w, int h, uint16_t* dst) {
    for (int row = 0; row < h; ++row) {
        const uint16_t* src = img.rgb.data() + (static_cast<size_t>(y + row) * img.width + x) * 3;
        std::memcpy(dst + static_cast<size_t>(row) * w * 3, src, static_cast<size_t>(w) * 6);
    }
}

}  // namespace rawboy

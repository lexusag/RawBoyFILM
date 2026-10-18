#include "rawboy.h"

namespace rawboy {

OrientedRow orientedRow(int o, int w, int h, int sy) {
    const bool swap = o >= 5 && o <= 8;
    const int ow = swap ? h : w;
    // Destination (x, y) of source (sx, sy): the inverse of each orientation's mapping.
    auto dest = [&](int sx) -> size_t {
        int x, y;
        switch (o) {
            case 2: x = w - 1 - sx; y = sy; break;          // mirror horizontal
            case 3: x = w - 1 - sx; y = h - 1 - sy; break;  // rotate 180
            case 4: x = sx; y = h - 1 - sy; break;          // mirror vertical
            case 5: x = sy; y = sx; break;                  // transpose
            case 6: x = h - 1 - sy; y = sx; break;          // rotate 90 CW
            case 7: x = h - 1 - sy; y = w - 1 - sx; break;  // transverse
            case 8: x = sy; y = w - 1 - sx; break;          // rotate 90 CCW
            default: x = sx; y = sy; break;
        }
        return (static_cast<size_t>(y) * ow + x) * 3;
    };
    const size_t start = dest(0);
    const ptrdiff_t step = w > 1 ? static_cast<ptrdiff_t>(dest(1)) - static_cast<ptrdiff_t>(start) : 3;
    return {start, step};
}

void applyOrientation(DecodedImage& image, int o) {
    if (o <= 1 || o > 8) return;
    const int w = image.width;
    const int h = image.height;
    const bool swap = o >= 5;
    std::vector<uint16_t> dst(image.rgb.size());
    parallelRows(h, [&](int rowBegin, int rowEnd) {
        for (int sy = rowBegin; sy < rowEnd; ++sy) {
            const OrientedRow row = orientedRow(o, w, h, sy);
            const uint16_t* s = image.rgb.data() + static_cast<size_t>(sy) * w * 3;
            uint16_t* d = dst.data() + row.start;
            for (int sx = 0; sx < w; ++sx, s += 3, d += row.step) {
                d[0] = s[0];
                d[1] = s[1];
                d[2] = s[2];
            }
        }
    });
    image.rgb.swap(dst);
    image.width = swap ? h : w;
    image.height = swap ? w : h;
}

}  // namespace rawboy

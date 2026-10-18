// Automatic blemish removal: detection of small round dark/red spots on skin,
// and spot healing (smooth tone fill + transplanted skin texture).
// Prototyped against a Fujifilm GFX 50R portrait; see BlemishRemover.kt for use.

#include "blemish.h"

#include <algorithm>
#include <cmath>

namespace rawboy {
namespace {

using Plane = std::vector<float>;

/** One pass of a box blur with the given radius along rows, then columns. */
void boxBlur(Plane& img, int w, int h, int radius) {
    if (radius < 1) return;
    Plane tmp(img.size());
    const float inv = 1.0f / (2 * radius + 1);
    for (int y = 0; y < h; ++y) {
        const float* in = img.data() + static_cast<size_t>(y) * w;
        float* out = tmp.data() + static_cast<size_t>(y) * w;
        float acc = 0;
        for (int i = -radius; i <= radius; ++i) acc += in[std::clamp(i, 0, w - 1)];
        for (int x = 0; x < w; ++x) {
            out[x] = acc * inv;
            acc += in[std::min(x + radius + 1, w - 1)] - in[std::max(x - radius, 0)];
        }
    }
    for (int x = 0; x < w; ++x) {
        float acc = 0;
        for (int i = -radius; i <= radius; ++i) acc += tmp[static_cast<size_t>(std::clamp(i, 0, h - 1)) * w + x];
        for (int y = 0; y < h; ++y) {
            img[static_cast<size_t>(y) * w + x] = acc * inv;
            acc += tmp[static_cast<size_t>(std::min(y + radius + 1, h - 1)) * w + x] -
                   tmp[static_cast<size_t>(std::max(y - radius, 0)) * w + x];
        }
    }
}

/** Gaussian approximated by three box blurs. */
Plane gaussian(const Plane& src, int w, int h, float sigma) {
    Plane out = src;
    if (sigma < 0.5f) return out;
    const int radius = std::max(1, static_cast<int>(std::lround((std::sqrt(4.0f * sigma * sigma + 1.0f) - 1.0f) / 2.0f)));
    for (int i = 0; i < 3; ++i) boxBlur(out, w, h, radius);
    return out;
}

/** Bilinear sample of the skin mask at full-resolution position (x, y). */
float maskAt(const SkinMaskView& m, float x, float y, int fullW, int fullH) {
    const float fx = std::clamp(x / fullW * m.width - 0.5f, 0.0f, m.width - 1.0f);
    const float fy = std::clamp(y / fullH * m.height - 0.5f, 0.0f, m.height - 1.0f);
    const int x0 = std::min(static_cast<int>(fx), m.width - 2 < 0 ? 0 : m.width - 2);
    const int y0 = std::min(static_cast<int>(fy), m.height - 2 < 0 ? 0 : m.height - 2);
    const int x1 = std::min(x0 + 1, m.width - 1);
    const int y1 = std::min(y0 + 1, m.height - 1);
    const float tx = fx - x0, ty = fy - y0;
    auto v = [&](int xx, int yy) { return m.data[static_cast<size_t>(yy) * m.width + xx]; };
    return (v(x0, y0) * (1 - tx) + v(x1, y0) * tx) * (1 - ty) + (v(x0, y1) * (1 - tx) + v(x1, y1) * tx) * ty;
}

}  // namespace

std::vector<Blemish> detectBlemishes(const DecodedImage& img, const SkinMaskView& mask, float faceDiameter) {
    std::vector<Blemish> spots;
    const int W = img.width, H = img.height;
    if (faceDiameter <= 0 || mask.width < 2 || mask.height < 2) return spots;

    // Face/skin bounding box in full-resolution pixels.
    int bx0 = W, by0 = H, bx1 = 0, by1 = 0;
    for (int y = 0; y < mask.height; ++y)
        for (int x = 0; x < mask.width; ++x)
            if (mask.data[static_cast<size_t>(y) * mask.width + x] > 0.5f) {
                bx0 = std::min(bx0, x * W / mask.width);
                by0 = std::min(by0, y * H / mask.height);
                bx1 = std::max(bx1, (x + 1) * W / mask.width);
                by1 = std::max(by1, (y + 1) * H / mask.height);
            }
    if (bx1 <= bx0 || by1 <= by0) return spots;

    // Spot radii from 0.4% to 1.4% of the face diameter, detected at a reduced
    // resolution where the smallest spot is ~3 px.
    const float rmin = 0.004f * faceDiameter, rmax = 0.014f * faceDiameter;
    const int f = std::max(1, static_cast<int>(rmin / 3.0f));
    const int w = (bx1 - bx0) / f, h = (by1 - by0) / f;
    if (w < 16 || h < 16) return spots;

    // Signal: dark and/or red relative to the surroundings (log units).
    Plane sig(static_cast<size_t>(w) * h), maskW(sig.size());
    parallelRows(h, [&](int r0, int r1) {
        for (int y = r0; y < r1; ++y)
            for (int x = 0; x < w; ++x) {
                float acc[3] = {0, 0, 0};
                for (int dy = 0; dy < f; ++dy) {
                    const uint16_t* p = img.rgb.data() + (static_cast<size_t>(by0 + y * f + dy) * W + bx0 + x * f) * 3;
                    for (int dx = 0; dx < f; ++dx, p += 3)
                        for (int c = 0; c < 3; ++c) acc[c] += halfToFloat(p[c]);
                }
                const float L = std::max(0.2627f * acc[0] + 0.6780f * acc[1] + 0.0593f * acc[2], 1e-6f);
                const float red = std::log(std::max(acc[0], 1e-6f) / std::max(acc[1], 1e-6f));
                sig[static_cast<size_t>(y) * w + x] = -std::log(L) + red;
                maskW[static_cast<size_t>(y) * w + x] =
                    maskAt(mask, bx0 + (x + 0.5f) * f, by0 + (y + 0.5f) * f, W, H);
            }
    });

    // Multi-scale centre-minus-surround response; keep the best scale per pixel.
    constexpr int kScales = 5;
    std::vector<Plane> responses(kScales);
    Plane best(sig.size(), -1e9f), bestR(sig.size(), 0.0f);
    std::vector<int> bestScale(sig.size(), 0);
    for (int k = 0; k < kScales; ++k) {
        const float r = rmin * std::pow(rmax / rmin, k / float(kScales - 1));
        const float s = r / std::sqrt(2.0f) / f;
        Plane centre = gaussian(sig, w, h, s);
        Plane surround = gaussian(sig, w, h, 3 * s);
        for (size_t i = 0; i < centre.size(); ++i) {
            centre[i] -= surround[i];
            if (centre[i] > best[i]) {
                best[i] = centre[i];
                bestR[i] = r;
                bestScale[i] = k;
            }
        }
        responses[k] = std::move(centre);
    }

    // Significance: how much a response stands out from the local spread (pores, noise).
    const int win = std::max(3, static_cast<int>(rmax * 8 / f) / 2);
    Plane mean = best, sq(best.size());
    for (size_t i = 0; i < best.size(); ++i) sq[i] = best[i] * best[i];
    boxBlur(mean, w, h, win);
    boxBlur(sq, w, h, win);

    const int nms = std::max(1, static_cast<int>(rmin * 1.5f / f));
    for (int y = nms; y < h - nms; ++y) {
        for (int x = nms; x < w - nms; ++x) {
            const size_t i = static_cast<size_t>(y) * w + x;
            const float b = best[i];
            // Too faint, or too strong: a hole in the face (nostril, tear duct, mouth corner)
            // or a mole, which is part of the person, not a blemish.
            if (b < 0.03f || b > 0.5f || maskW[i] < 0.8f) continue;
            const float sd = std::sqrt(std::max(sq[i] - mean[i] * mean[i], 1e-6f));
            const float z = (b - mean[i]) / sd;
            if (z < 3.0f) continue;
            bool isMax = true;
            for (int dy = -nms; dy <= nms && isMax; ++dy)
                for (int dx = -nms; dx <= nms; ++dx)
                    if ((dx || dy) && best[i + static_cast<ptrdiff_t>(dy) * w + dx] > b) { isMax = false; break; }
            if (!isMax) continue;

            // Round, not a hair or an edge: Hessian of the response at its scale.
            const Plane& d = responses[bestScale[i]];
            const float dxx = d[i + 1] - 2 * d[i] + d[i - 1];
            const float dyy = d[i + w] - 2 * d[i] + d[i - w];
            const float dxy = (d[i + w + 1] - d[i + w - 1] - d[i - w + 1] + d[i - w - 1]) / 4;
            const float tr = dxx + dyy, det = dxx * dyy - dxy * dxy;
            if (det <= 0 || tr * tr / det > 5.0f) continue;

            // The whole neighbourhood must be skin (keeps away from eyes, lips, hairline).
            const float r = bestR[i];
            const float fx = bx0 + (x + 0.5f) * f, fy = by0 + (y + 0.5f) * f;
            bool skin = true;
            for (int a = 0; a < 8 && skin; ++a) {
                const float ang = a * 0.785398f;
                skin = maskAt(mask, fx + std::cos(ang) * 2.5f * r, fy + std::sin(ang) * 2.5f * r, W, H) >= 0.6f;
            }
            if (!skin) continue;
            spots.push_back({fx, fy, r, z});
        }
    }
    std::sort(spots.begin(), spots.end(), [](const Blemish& a, const Blemish& b) { return a.score > b.score; });
    return spots;
}

void healBlemish(DecodedImage& img, const Blemish& spot, std::vector<HealBackup>& backups) {
    const int W = img.width, H = img.height;
    const float R = std::max(3.0f, 2.0f * spot.radius);
    const int B = static_cast<int>(std::ceil(R * 3.6f)) + 2;
    const int cx = static_cast<int>(spot.x), cy = static_cast<int>(spot.y);
    if (cx - B < 0 || cy - B < 0 || cx + B >= W || cy + B >= H) return;
    const int n = 2 * B + 1;

    // Patch in float, and its backup.
    HealBackup backup{cx - B, cy - B, n, n, {}};
    backup.pixels.resize(static_cast<size_t>(n) * n * 3);
    std::vector<Plane> P(3, Plane(static_cast<size_t>(n) * n));
    for (int y = 0; y < n; ++y)
        for (int x = 0; x < n; ++x) {
            const size_t src = (static_cast<size_t>(cy - B + y) * W + cx - B + x) * 3;
            for (int c = 0; c < 3; ++c) {
                backup.pixels[(static_cast<size_t>(y) * n + x) * 3 + c] = img.rgb[src + c];
                P[c][static_cast<size_t>(y) * n + x] = halfToFloat(img.rgb[src + c]);
            }
        }

    auto dist = [&](int x, int y) { return std::hypot(float(x - B), float(y - B)); };
    std::vector<Plane> smooth(3), low(3);
    for (int c = 0; c < 3; ++c) smooth[c] = gaussian(P[c], n, n, spot.radius * 0.5f);

    // Low frequency: membrane (Laplace) fill of the smoothed image over the disk.
    for (int c = 0; c < 3; ++c) {
        low[c] = smooth[c];
        double ringSum = 0;
        int ringCount = 0;
        for (int y = 0; y < n; ++y)
            for (int x = 0; x < n; ++x) {
                const float d = dist(x, y);
                if (d >= R && d < R + 2) { ringSum += smooth[c][static_cast<size_t>(y) * n + x]; ++ringCount; }
            }
        const float ring = ringCount ? float(ringSum / ringCount) : 0.0f;
        for (int y = 0; y < n; ++y)
            for (int x = 0; x < n; ++x)
                if (dist(x, y) < R) low[c][static_cast<size_t>(y) * n + x] = ring;
        const int iterations = static_cast<int>(R * 3) + 30;
        Plane next = low[c];
        for (int it = 0; it < iterations; ++it) {
            for (int y = 1; y < n - 1; ++y)
                for (int x = 1; x < n - 1; ++x) {
                    if (dist(x, y) >= R) continue;
                    const size_t i = static_cast<size_t>(y) * n + x;
                    next[i] = 0.25f * (low[c][i - 1] + low[c][i + 1] + low[c][i - n] + low[c][i + n]);
                }
            std::swap(low[c], next);
        }
    }

    // Texture donor: the cleanest same-size disk 2.2R away, in one of 8 directions.
    int bestDx = 0, bestDy = 0;
    float bestCost = 1e30f;
    double lowMean = 0;
    int lowCount = 0;
    for (int y = 0; y < n; ++y)
        for (int x = 0; x < n; ++x)
            if (dist(x, y) < R) { lowMean += low[1][static_cast<size_t>(y) * n + x]; ++lowCount; }
    lowMean /= std::max(lowCount, 1);
    for (int a = 0; a < 8; ++a) {
        const int dx = static_cast<int>(std::lround(std::cos(a * 0.785398) * 2.2 * R));
        const int dy = static_cast<int>(std::lround(std::sin(a * 0.785398) * 2.2 * R));
        double s = 0, s2 = 0, tone = 0;
        int count = 0;
        for (int y = 0; y < n; ++y)
            for (int x = 0; x < n; ++x) {
                if (dist(x, y) >= R) continue;
                const int sx = x + dx, sy = y + dy;
                if (sx < 0 || sy < 0 || sx >= n || sy >= n) { count = -1; break; }
                const size_t j = static_cast<size_t>(sy) * n + sx;
                const float hf = std::log(std::max(P[1][j], 1e-5f)) - std::log(std::max(smooth[1][j], 1e-5f));
                s += hf; s2 += hf * hf; tone += smooth[1][j];
                ++count;
            }
        if (count <= 0) continue;
        const double sd = std::sqrt(std::max(0.0, s2 / count - (s / count) * (s / count)));
        const double cost = sd + std::fabs(std::log(tone / count + 1e-6) - std::log(lowMean + 1e-6));
        if (cost < bestCost) { bestCost = static_cast<float>(cost); bestDx = dx; bestDy = dy; }
    }

    // Composite: membrane tone x donor texture, feathered into the original.
    for (int y = 0; y < n; ++y)
        for (int x = 0; x < n; ++x) {
            const float d = dist(x, y);
            if (d >= R) continue;
            float a = std::clamp((R - d) / (0.4f * R), 0.0f, 1.0f);
            a = a * a * (3 - 2 * a);
            const int sx = std::clamp(x + bestDx, 0, n - 1), sy = std::clamp(y + bestDy, 0, n - 1);
            const size_t i = static_cast<size_t>(y) * n + x, j = static_cast<size_t>(sy) * n + sx;
            const size_t dst = (static_cast<size_t>(cy - B + y) * W + cx - B + x) * 3;
            for (int c = 0; c < 3; ++c) {
                const float tex = std::clamp(P[c][j] / std::max(smooth[c][j], 1e-5f), 0.6f, 1.6f);
                const float healed = low[c][i] * tex;
                img.rgb[dst + c] = floatToHalf(P[c][i] * (1 - a) + healed * a);
            }
        }
    backups.push_back(std::move(backup));
}

void restoreBackups(DecodedImage& img, std::vector<HealBackup>& backups) {
    // Newest first, so overlapping patches unwind correctly.
    for (auto it = backups.rbegin(); it != backups.rend(); ++it) {
        for (int y = 0; y < it->height; ++y) {
            uint16_t* dst = img.rgb.data() + (static_cast<size_t>(it->y + y) * img.width + it->x) * 3;
            std::copy_n(it->pixels.data() + static_cast<size_t>(y) * it->width * 3, it->width * 3, dst);
        }
    }
    backups.clear();
}

}  // namespace rawboy

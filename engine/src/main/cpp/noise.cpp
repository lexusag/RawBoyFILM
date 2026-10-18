// Image-based noise estimate for the auto denoise (AutoTone), measured in the domain the develop shader's denoise()
// works in: sqrt of the decoded linear values, where shot noise has roughly the same std at every level.
//
// Patches spread over the full-resolution image are split into 16x16 blocks. Per block: the std of luma minus its
// Gaussian blur (sigma 1.5 px, the bilateral's spatial weight) and the RMS of chroma minus its blur at sigma 6 px
// (colour noise is blotchy, a few pixels across after demosaicing). Texture only adds to these, so the quietest blocks
// at each brightness tell the noise: per 1 EV band of block brightness the 10th percentile, averaged over the bands by
// their share of blocks (deep shadows with read noise count as much as the image has of them).

#include "rawboy.h"

#include <algorithm>
#include <cmath>
#include <vector>

namespace rawboy {
namespace {

constexpr int kBlock = 16;
constexpr int kBlocksPerSide = 3;
constexpr int kInner = kBlock * kBlocksPerSide;  // 48
constexpr int kMargin = 18;                      // 3 sigma of the chroma blur
constexpr int kPatch = kInner + 2 * kMargin;     // 84
constexpr float kLumaSigma = 1.5f;
constexpr float kChromaSigma = 6.0f;
constexpr float kMinLevel = 0.0005f;  // blocks darker than this are mostly black clipping
constexpr float kMaxValue = 0.95f;    // blocks touching sensor clip have no noise left
constexpr int kMinBlocksPerBand = 8;
constexpr float kPercentile = 0.1f;

struct BlockStat {
    float level;   // log2 of the block's mean linear value
    float luma;    // luma residual std
    float chroma;  // chroma residual RMS
};

std::vector<float> gaussianKernel(float sigma) {
    const int r = static_cast<int>(std::ceil(sigma * 3.0f));
    std::vector<float> k(2 * r + 1);
    float sum = 0.0f;
    for (int i = -r; i <= r; ++i) sum += k[i + r] = std::exp(-0.5f * i * i / (sigma * sigma));
    for (float& v : k) v /= sum;
    return k;
}

/** Separable blur of [src] (n x n, [channels] interleaved), valid only [r] px in from the edges; [tmp] same size. */
void blur(const std::vector<float>& src, std::vector<float>& tmp, std::vector<float>& dst, int n, int channels,
          const std::vector<float>& k) {
    const int r = static_cast<int>(k.size() / 2);
    std::fill(tmp.begin(), tmp.end(), 0.0f);
    std::fill(dst.begin(), dst.end(), 0.0f);
    for (int y = 0; y < n; ++y) {
        for (int x = r; x < n - r; ++x) {
            for (int c = 0; c < channels; ++c) {
                float s = 0.0f;
                for (int i = -r; i <= r; ++i) s += k[i + r] * src[(y * n + x + i) * channels + c];
                tmp[(y * n + x) * channels + c] = s;
            }
        }
    }
    for (int y = r; y < n - r; ++y) {
        for (int x = r; x < n - r; ++x) {
            for (int c = 0; c < channels; ++c) {
                float s = 0.0f;
                for (int i = -r; i <= r; ++i) s += k[i + r] * tmp[((y + i) * n + x) * channels + c];
                dst[(y * n + x) * channels + c] = s;
            }
        }
    }
}

void measurePatch(const DecodedImage& img, int px, int py, const std::vector<float>& lumaK,
                  const std::vector<float>& chromaK, std::vector<BlockStat>& out) {
    const int n = kPatch;
    std::vector<float> lin(n * n), maxc(n * n), luma(n * n), chroma(n * n * 3);
    for (int y = 0; y < n; ++y) {
        const uint16_t* row = img.rgb.data() + (static_cast<size_t>(py + y) * img.width + px) * 3;
        for (int x = 0; x < n; ++x, row += 3) {
            const float r = halfToFloat(row[0]), g = halfToFloat(row[1]), b = halfToFloat(row[2]);
            const float sr = std::sqrt(std::max(r, 0.0f)), sg = std::sqrt(std::max(g, 0.0f)),
                        sb = std::sqrt(std::max(b, 0.0f));
            const float l = (sr + sg + sb) / 3.0f;
            const int i = y * n + x;
            lin[i] = (r + g + b) / 3.0f;
            maxc[i] = std::max({r, g, b});
            luma[i] = l;
            chroma[i * 3] = sr - l;
            chroma[i * 3 + 1] = sg - l;
            chroma[i * 3 + 2] = sb - l;
        }
    }
    std::vector<float> tmp1(n * n), lumaBlur(n * n), tmp3(n * n * 3), chromaBlur(n * n * 3);
    blur(luma, tmp1, lumaBlur, n, 1, lumaK);
    blur(chroma, tmp3, chromaBlur, n, 3, chromaK);
    for (int by = 0; by < kBlocksPerSide; ++by) {
        for (int bx = 0; bx < kBlocksPerSide; ++bx) {
            double sumLin = 0, sumR = 0, sumR2 = 0, sumC2 = 0;
            float peak = 0.0f;
            for (int y = 0; y < kBlock; ++y) {
                for (int x = 0; x < kBlock; ++x) {
                    const int i = (kMargin + by * kBlock + y) * n + kMargin + bx * kBlock + x;
                    sumLin += lin[i];
                    peak = std::max(peak, maxc[i]);
                    const float d = luma[i] - lumaBlur[i];
                    sumR += d;
                    sumR2 += d * d;
                    for (int c = 0; c < 3; ++c) {
                        const float e = chroma[i * 3 + c] - chromaBlur[i * 3 + c];
                        sumC2 += e * e;
                    }
                }
            }
            constexpr double count = kBlock * kBlock;
            const double mean = sumLin / count;
            if (!(mean > kMinLevel) || !(peak < kMaxValue)) continue;
            const double meanR = sumR / count;
            out.push_back({static_cast<float>(std::log2(mean)),
                           static_cast<float>(std::sqrt(std::max(0.0, sumR2 / count - meanR * meanR))),
                           static_cast<float>(std::sqrt(sumC2 / (count * 3)))});
        }
    }
}

/** Area-weighted mean over 1 EV brightness bands of each band's low percentile of [value]. */
float bandedFloor(const std::vector<BlockStat>& blocks, float BlockStat::*value) {
    double num = 0, den = 0;
    std::vector<float> v;
    for (int band = -12; band < 1; ++band) {
        v.clear();
        for (const auto& b : blocks) {
            if (b.level >= band && b.level < band + 1) v.push_back(b.*value);
        }
        if (static_cast<int>(v.size()) < kMinBlocksPerBand) continue;
        // numpy's default (linear) percentile
        std::sort(v.begin(), v.end());
        const float pos = kPercentile * static_cast<float>(v.size() - 1);
        const size_t lo = static_cast<size_t>(pos);
        const size_t hi = std::min(lo + 1, v.size() - 1);
        const float p = v[lo] + (v[hi] - v[lo]) * (pos - static_cast<float>(lo));
        num += static_cast<double>(p) * v.size();
        den += static_cast<double>(v.size());
    }
    return den > 0 ? static_cast<float>(num / den) : 0.0f;
}

}  // namespace

NoiseLevel estimateNoise(const DecodedImage& img) {
    if (img.displayReferred || img.width < kPatch || img.height < kPatch) return {};
    // About 400 patches (3600 blocks, ~2.8 Mpx examined) on a grid with the image's aspect ratio.
    const float aspect = static_cast<float>(img.width) / static_cast<float>(img.height);
    const int gx = std::clamp(static_cast<int>(std::round(std::sqrt(400.0f * aspect))), 1, img.width / kPatch);
    const int gy = std::clamp(static_cast<int>(std::round(400.0f / static_cast<float>(gx))), 1, img.height / kPatch);
    const auto lumaK = gaussianKernel(kLumaSigma);
    const auto chromaK = gaussianKernel(kChromaSigma);
    std::vector<std::vector<BlockStat>> perRow(gy);
    parallelRows(gy, [&](int rowBegin, int rowEnd) {
        for (int j = rowBegin; j < rowEnd; ++j) {
            const int py = static_cast<int>((static_cast<int64_t>(img.height - kPatch) * (2 * j + 1)) / (2 * gy));
            for (int i = 0; i < gx; ++i) {
                const int px = static_cast<int>((static_cast<int64_t>(img.width - kPatch) * (2 * i + 1)) / (2 * gx));
                measurePatch(img, px, py, lumaK, chromaK, perRow[j]);
            }
        }
    });
    std::vector<BlockStat> blocks;
    for (const auto& r : perRow) blocks.insert(blocks.end(), r.begin(), r.end());
    return {bandedFloor(blocks, &BlockStat::luma), bandedFloor(blocks, &BlockStat::chroma)};
}

}  // namespace rawboy

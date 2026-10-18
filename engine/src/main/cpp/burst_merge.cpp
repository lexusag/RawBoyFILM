#include "burst_merge.h"

#include "rawboy.h"  // parallelRows

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>

namespace rawboy {

namespace {

constexpr float PI_F = 3.14159265358979f;

using Plane = BurstMerger::Plane;
using Shift = BurstMerger::Shift;

/** Box-downsampled copy, [f] x [f] pixels -> 1 (edge pixels repeat where the size isn't a multiple). */
Plane downsample(const Plane& in, int f) {
    Plane out;
    out.w = std::max(1, (in.w + f - 1) / f);
    out.h = std::max(1, (in.h + f - 1) / f);
    out.px.assign(static_cast<size_t>(out.w) * out.h, 0.0f);
    const float inv = 1.0f / static_cast<float>(f * f);
    parallelRows(out.h, [&](int y0, int y1) {
        for (int y = y0; y < y1; ++y) {
            for (int x = 0; x < out.w; ++x) {
                float s = 0.0f;
                for (int j = 0; j < f; ++j) {
                    for (int i = 0; i < f; ++i) s += in.at(x * f + i, y * f + j);
                }
                out.px[static_cast<size_t>(y) * out.w + x] = s * inv;
            }
        }
    });
    return out;
}

std::vector<Plane> pyramid(Plane l0) {
    std::vector<Plane> p(4);
    p[0] = std::move(l0);
    p[1] = downsample(p[0], 2);
    p[2] = downsample(p[1], 4);
    p[3] = downsample(p[2], 4);
    return p;
}

/**
 * Raised-cosine window over one merge tile, per grey pixel (2x2 block) [k] of the tile: tiles overlapping at half
 * stride sum to exactly 1, and the window is constant within a block, so the block's weight sum needs one value.
 */
float window(int k) {
    const float s = std::sin(PI_F * (2.0f * static_cast<float>(k) + 1.0f) / BurstMerger::MERGE_TILE);
    return s * s;
}

/**
 * Grey noise variance from the difference of two frames: per 8x8 block the variance of (b - a) minus its mean, the
 * 10th percentile over blocks (texture and motion only add), halved (two frames).
 */
float estimateNoise(const Plane& a, const Plane& b) {
    std::vector<float> vars;
    for (int by = 0; by + 8 <= a.h; by += 8) {
        for (int bx = 0; bx + 8 <= a.w; bx += 8) {
            float s = 0.0f, s2 = 0.0f;
            for (int y = 0; y < 8; ++y) {
                for (int x = 0; x < 8; ++x) {
                    const float d = b.at(bx + x, by + y) - a.at(bx + x, by + y);
                    s += d;
                    s2 += d * d;
                }
            }
            vars.push_back(std::max(0.0f, s2 / 64.0f - (s / 64.0f) * (s / 64.0f)));
        }
    }
    if (vars.empty()) return 1e-6f;
    const size_t k = vars.size() / 10;
    std::nth_element(vars.begin(), vars.begin() + k, vars.end());
    return std::max(1e-8f, vars[k] * 0.5f);
}

}  // namespace

BurstMerger::BurstMerger(int width, int height, const float black[4], float white, float noiseScale, float noiseOffset,
                         bool levelsKnown)
    : width_(width), height_(height), white_(white), noiseScale_(noiseScale), noiseOffset_(noiseOffset),
      tilesX_((width + MERGE_STRIDE - 1) / MERGE_STRIDE + 1), tilesY_((height + MERGE_STRIDE - 1) / MERGE_STRIDE + 1),
      levelsKnown_(levelsKnown) {
    std::memcpy(black_, black, sizeof black_);
    worker_ = std::thread([this] { run(); });
}

BurstMerger::~BurstMerger() {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        finishing_ = true;
    }
    changed_.notify_all();
    if (worker_.joinable()) worker_.join();
}

int BurstMerger::addFrame(const uint8_t* data, int rowStride, double gain) {
    auto f = std::make_unique<Frame>();
    f->raw.resize(static_cast<size_t>(width_) * height_);
    for (int y = 0; y < height_; ++y) {
        std::memcpy(f->raw.data() + static_cast<size_t>(y) * width_, data + static_cast<size_t>(y) * rowStride,
                    static_cast<size_t>(width_) * sizeof(uint16_t));
    }
    f->gain = gain;
    int index;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (finishing_) return -1;
        index = static_cast<int>(frames_.size());
        frames_.push_back(std::move(f));
        maxQueued_ = std::max(maxQueued_, ++queued_);
    }
    changed_.notify_all();
    return index;
}

void BurstMerger::setGain(int frame, double gain) {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        // A frame already being merged keeps the exposure it started with.
        if (frame < static_cast<int>(next_) || frame >= static_cast<int>(frames_.size()) || !(gain > 0.0)) return;
        frames_[frame]->gain = gain;
    }
    changed_.notify_all();
}

void BurstMerger::setLevels(const float* black, float noiseScale, float noiseOffset) {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (levelsKnown_ || finishing_) return;  // the worker may be reading them
        if (black) std::memcpy(black_, black, sizeof black_);
        noiseScale_ = noiseScale;
        noiseOffset_ = noiseOffset;
        levelsKnown_ = true;
    }
    changed_.notify_all();
}

int BurstMerger::frames() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return static_cast<int>(frames_.size());
}

std::vector<float> BurstMerger::frameWeights() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return frameWeights_;
}

int BurstMerger::maxQueued() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return maxQueued_;
}

void BurstMerger::run() {
    for (;;) {
        Frame* f;
        size_t index;
        {
            std::unique_lock<std::mutex> lock(mutex_);
            // Frames merge in order, each once its exposure and the reference's levels are known (or the burst ended).
            changed_.wait(lock, [this] {
                if (next_ >= frames_.size()) return finishing_;
                return (levelsKnown_ || finishing_) && (frames_[next_]->gain > 0.0 || finishing_);
            });
            if (next_ >= frames_.size()) return;
            index = next_;
            f = frames_[index].get();
            if (!(f->gain > 0.0)) f->gain = index == 0 ? 1.0 : refGain_;
        }
        if (index == 0) {
            startReference(*f);
        } else {
            mergeFrame(*f, index);
            std::vector<uint16_t>().swap(f->raw);
        }
        {
            std::lock_guard<std::mutex> lock(mutex_);
            f->merged = true;
            ++next_;
            --queued_;
        }
    }
}

BurstMerger::Plane BurstMerger::grey(const std::vector<uint16_t>& raw, double gain) const {
    // Mean of each 2x2 CFA block, black subtracted, in units of the white-black range, at the reference's exposure.
    const float meanBlack = (black_[0] + black_[1] + black_[2] + black_[3]) * 0.25f;
    const float scale = static_cast<float>(refGain_ / gain) / std::max(1.0f, white_ - meanBlack) * 0.25f;
    const float blackSum = black_[0] + black_[1] + black_[2] + black_[3];
    Plane p;
    p.w = width_ / 2;
    p.h = height_ / 2;
    p.px.resize(static_cast<size_t>(p.w) * p.h);
    parallelRows(p.h, [&](int y0, int y1) {
        for (int y = y0; y < y1; ++y) {
            const uint16_t* r0 = raw.data() + static_cast<size_t>(2 * y) * width_;
            const uint16_t* r1 = r0 + width_;
            for (int x = 0; x < p.w; ++x) {
                const float s = static_cast<float>(r0[2 * x]) + r0[2 * x + 1] + r1[2 * x] + r1[2 * x + 1] - blackSum;
                p.px[static_cast<size_t>(y) * p.w + x] = s * scale;
            }
        }
    });
    return p;
}

void BurstMerger::startReference(Frame& f) {
    ref_ = &f;
    refGain_ = f.gain;
    refPyramid_ = pyramid(grey(f.raw, f.gain));
    // The reference counts with weight 1 everywhere (the windows sum to 1).
    num_.resize(f.raw.size());
    parallelRows(height_, [&](int y0, int y1) {
        for (int y = y0; y < y1; ++y) {
            for (int x = 0; x < width_; ++x) {
                const size_t i = static_cast<size_t>(y) * width_ + x;
                num_[i] = static_cast<float>(f.raw[i]) - black_[(y & 1) * 2 + (x & 1)];
            }
        }
    });
    denom_.assign(static_cast<size_t>(width_ / 2) * (height_ / 2), 1.0f);
}

std::vector<BurstMerger::Shift> BurstMerger::align(const std::vector<Plane>& alt) const {
    // Coarse to fine: levels 3, 2, 1 use 8 px tiles side by side; level 0 uses the merge tiles (16 grey px, stride 8,
    // starting half a tile before the edge, as the merge does).
    const std::vector<Plane>& ref = refPyramid_;
    struct Grid { int originOffset, size, stride, nx, ny; };
    auto gridFor = [&](int level) {
        const Plane& p = ref[level];
        if (level == 0) return Grid{-GREY_STRIDE, GREY_TILE, GREY_STRIDE, tilesX_, tilesY_};
        return Grid{0, 8, 8, (p.w + 7) / 8, (p.h + 7) / 8};
    };
    static constexpr int FACTOR[4] = {1, 2, 4, 4};  // level L is FACTOR[L] x smaller than level L-1
    static constexpr int RADIUS[4] = {1, 4, 4, 4};

    // Sum of squared differences of one tile at a displacement; reads clamp at the edges.
    auto error = [](const Plane& r, const Plane& a, int ox, int oy, int size, int dx, int dy, float bound) {
        float e = 0.0f;
        if (ox >= 0 && oy >= 0 && ox + size <= r.w && oy + size <= r.h && ox + dx >= 0 && oy + dy >= 0 &&
            ox + dx + size <= a.w && oy + dy + size <= a.h) {
            // Inside both planes (most tiles): no clamping.
            for (int y = 0; y < size; ++y) {
                const float* rp = &r.px[static_cast<size_t>(oy + y) * r.w + ox];
                const float* ap = &a.px[static_cast<size_t>(oy + y + dy) * a.w + ox + dx];
                for (int x = 0; x < size; ++x) {
                    const float d = ap[x] - rp[x];
                    e += d * d;
                }
                if (e >= bound) break;
            }
            return e;
        }
        for (int y = 0; y < size; ++y) {
            for (int x = 0; x < size; ++x) {
                const float d = a.at(ox + x + dx, oy + y + dy) - r.at(ox + x, oy + y);
                e += d * d;
            }
            if (e >= bound) break;
        }
        return e;
    };

    std::vector<Shift> coarse;
    Grid coarseGrid{};
    for (int level = 3; level >= 0; --level) {
        const Grid g = gridFor(level);
        const Plane& r = ref[level];
        const Plane& a = alt[level];
        std::vector<Shift> shifts(static_cast<size_t>(g.nx) * g.ny);
        const int f = level < 3 ? FACTOR[level + 1] : 1;
        const int radius = RADIUS[level];
        parallelRows(g.ny, [&](int j0, int j1) {
            for (int j = j0; j < j1; ++j) {
                for (int i = 0; i < g.nx; ++i) {
                    const int ox = g.originOffset + i * g.stride;
                    const int oy = g.originOffset + j * g.stride;
                    // Candidates from the coarser level: the tile under this one's centre and its nearest neighbours
                    // in x and y (helps at the edges of moving things).
                    Shift guess;
                    if (!coarse.empty()) {
                        const float cx = (static_cast<float>(ox) + g.size * 0.5f) / f;
                        const float cy = (static_cast<float>(oy) + g.size * 0.5f) / f;
                        const float tx = (cx - coarseGrid.originOffset) / coarseGrid.stride - 0.5f;
                        const float ty = (cy - coarseGrid.originOffset) / coarseGrid.stride - 0.5f;
                        const int ci = std::clamp(static_cast<int>(std::lround(tx)), 0, coarseGrid.nx - 1);
                        const int cj = std::clamp(static_cast<int>(std::lround(ty)), 0, coarseGrid.ny - 1);
                        const int ni = std::clamp(ci + (tx > ci ? 1 : -1), 0, coarseGrid.nx - 1);
                        const int nj = std::clamp(cj + (ty > cj ? 1 : -1), 0, coarseGrid.ny - 1);
                        const Shift cands[3] = {coarse[static_cast<size_t>(cj) * coarseGrid.nx + ci],
                                                coarse[static_cast<size_t>(cj) * coarseGrid.nx + ni],
                                                coarse[static_cast<size_t>(nj) * coarseGrid.nx + ci]};
                        float best = std::numeric_limits<float>::max();
                        for (const Shift& c : cands) {
                            const float e = error(r, a, ox, oy, g.size, c.dx * f, c.dy * f, best);
                            if (e < best) {
                                best = e;
                                guess = {c.dx * f, c.dy * f};
                            }
                        }
                    }
                    Shift found = guess;
                    float best = std::numeric_limits<float>::max();
                    for (int dy = -radius; dy <= radius; ++dy) {
                        for (int dx = -radius; dx <= radius; ++dx) {
                            const float e = error(r, a, ox, oy, g.size, guess.dx + dx, guess.dy + dy, best);
                            // Ties go to the smaller shift (flat tiles stay put).
                            if (e < best || (e == best && std::abs(guess.dx + dx) + std::abs(guess.dy + dy) <
                                                              std::abs(found.dx) + std::abs(found.dy))) {
                                best = e;
                                found = {guess.dx + dx, guess.dy + dy};
                            }
                        }
                    }
                    shifts[static_cast<size_t>(j) * g.nx + i] = found;
                }
            }
        });
        coarse = std::move(shifts);
        coarseGrid = g;
    }
    return coarse;
}

float BurstMerger::greyVar(float level) const {
    // The sensor's model is per sample; the grey copy is a mean of 4.
    if (flatVar_ > 0.0f) return flatVar_;
    return std::max(1e-9f, (noiseScale_ * std::max(0.0f, level) + noiseOffset_) * 0.25f);
}

void BurstMerger::mergeFrame(Frame& f, size_t index) {
    const std::vector<Plane> alt = pyramid(grey(f.raw, f.gain));
    const std::vector<Shift> shifts = align(alt);
    const Plane& a = alt[0];
    const Plane& r0 = refPyramid_[0];
    if (flatVar_ == 0.0f && !(noiseScale_ > 0.0f || noiseOffset_ > 0.0f)) flatVar_ = estimateNoise(r0, a);

    const std::vector<uint16_t>& refRaw = ref_->raw;  // the reference is never freed before finish()
    const float gain = static_cast<float>(refGain_ / f.gain);
    const float sat = white_ - 2.0f;  // samples this close to the clip count as clipped
    const int gw = width_ / 2;
    float win[GREY_TILE];
    for (int k = 0; k < GREY_TILE; ++k) win[k] = window(k);
    double weightSum = 0.0;

    // Tiles overlap their neighbours by half; rows two apart don't, so even and odd tile rows run in two passes.
    for (int parity = 0; parity < 2; ++parity) {
        const int rows = (tilesY_ - parity + 1) / 2;
        std::vector<double> rowWeights(rows, 0.0);
        parallelRows(rows, [&](int k0, int k1) {
            std::vector<float> pixW(GREY_TILE * GREY_TILE);
            std::vector<float> diff((GREY_TILE + 2) * (GREY_TILE + 2));
            for (int k = k0; k < k1; ++k) {
                const int tj = parity + 2 * k;
                const int oy = -MERGE_STRIDE + tj * MERGE_STRIDE;
                for (int ti = 0; ti < tilesX_; ++ti) {
                    const int ox = -MERGE_STRIDE + ti * MERGE_STRIDE;
                    const int gx = ox / 2, gy = oy / 2;
                    const Shift s = shifts[static_cast<size_t>(tj) * tilesX_ + ti];

                    // Mean grey level of the reference tile (inside the image) sets the noise.
                    float mean = 0.0f;
                    int count = 0;
                    for (int y = std::max(0, gy); y < std::min(r0.h, gy + GREY_TILE); ++y) {
                        for (int x = std::max(0, gx); x < std::min(r0.w, gx + GREY_TILE); ++x) {
                            mean += r0.px[static_cast<size_t>(y) * r0.w + x];
                            ++count;
                        }
                    }
                    if (count == 0) continue;
                    const float var = greyVar(mean / count);
                    const float pixelVar = pixelSigmas * pixelSigmas * 2.0f * var / 9.0f;

                    // Tile weight: the aligned tile's mean squared difference beyond the noise of two frames.
                    float d2 = 0.0f;
                    int m = 0;
                    for (int y = -1; y <= GREY_TILE; ++y) {
                        for (int x = -1; x <= GREY_TILE; ++x) {
                            const float d = a.at(gx + x + s.dx, gy + y + s.dy) - r0.at(gx + x, gy + y);
                            diff[(y + 1) * (GREY_TILE + 2) + (x + 1)] = d;
                            const int px = gx + x, py = gy + y;
                            if (x >= 0 && y >= 0 && x < GREY_TILE && y < GREY_TILE && px >= 0 && py >= 0 &&
                                px < r0.w && py < r0.h) {
                                d2 += d * d;
                                ++m;
                            }
                        }
                    }
                    const float excess = std::max(0.0f, d2 / std::max(1, m) - 2.0f * var);
                    const float tileW = 1.0f / (1.0f + excess / (tileK * var));
                    rowWeights[k] += tileW;
                    // Pixel weight: the 3x3 mean difference against its own noise.
                    for (int y = 0; y < GREY_TILE; ++y) {
                        for (int x = 0; x < GREY_TILE; ++x) {
                            float md = 0.0f;
                            for (int j = 0; j < 3; ++j) {
                                for (int i = 0; i < 3; ++i) md += diff[(y + j) * (GREY_TILE + 2) + (x + i)];
                            }
                            md /= 9.0f;
                            const float q = md * md / pixelVar;
                            pixW[y * GREY_TILE + x] = tileW / (1.0f + q * q);
                        }
                    }

                    // Accumulate per 2x2 block: the weight (and window) is the block's, so the block's weight sum is
                    // one value. A clipped or out-of-frame sample counts as the reference's, which keeps the sums
                    // consistent and leaves that sample as the reference has it.
                    for (int qy = std::max(0, gy); qy < std::min(height_ / 2, gy + GREY_TILE); ++qy) {
                        const int ky = qy - gy;
                        for (int qx = std::max(0, gx); qx < std::min(gw, gx + GREY_TILE); ++qx) {
                            const int kx = qx - gx;
                            const float w = win[ky] * win[kx] * pixW[ky * GREY_TILE + kx];
                            if (w <= 0.0f) continue;
                            denom_[static_cast<size_t>(qy) * gw + qx] += w;
                            for (int j = 0; j < 2; ++j) {
                                const int y = 2 * qy + j;
                                const int ay = y + 2 * s.dy;
                                for (int i = 0; i < 2; ++i) {
                                    const int x = 2 * qx + i;
                                    const int ax = x + 2 * s.dx;
                                    const size_t pi = static_cast<size_t>(y) * width_ + x;
                                    const float b = black_[j * 2 + i];
                                    float v = static_cast<float>(refRaw[pi]) - b;
                                    if (ax >= 0 && ay >= 0 && ax < width_ && ay < height_) {
                                        const uint16_t av = f.raw[static_cast<size_t>(ay) * width_ + ax];
                                        if (av < sat) v = (static_cast<float>(av) - b) * gain;
                                    }
                                    num_[pi] += w * v;
                                }
                            }
                        }
                    }
                }
            }
        });
        for (double w : rowWeights) weightSum += w;
    }
    std::lock_guard<std::mutex> lock(mutex_);
    frameWeights_.resize(index, 0.0f);
    frameWeights_[index - 1] = static_cast<float>(weightSum / (static_cast<double>(tilesX_) * tilesY_));
}

const uint16_t* BurstMerger::finish() {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        finishing_ = true;
    }
    changed_.notify_all();
    if (worker_.joinable()) worker_.join();
    if (frames_.empty()) return nullptr;
    std::vector<uint16_t>& raw = frames_[0]->raw;
    if (finished_ || frames_.size() == 1) {
        finished_ = true;
        return raw.data();
    }
    // The result goes over the reference's samples (no second full-size buffer).
    const float sat = white_ - 2.0f;
    const float top = std::min(white_, 65535.0f);
    const int gw = width_ / 2, gh = height_ / 2;
    parallelRows(2 * gh, [&](int y0, int y1) {
        for (int y = y0; y < y1; ++y) {
            for (int x = 0; x < 2 * gw; ++x) {  // an odd last row or column stays the reference's
                const size_t i = static_cast<size_t>(y) * width_ + x;
                if (raw[i] >= sat) continue;  // clipped in the reference: stays clipped
                const float d = denom_[static_cast<size_t>(y >> 1) * gw + (x >> 1)];
                const float v = num_[i] / d + black_[(y & 1) * 2 + (x & 1)];
                raw[i] = static_cast<uint16_t>(std::clamp(std::lround(v), 0L, static_cast<long>(top)));
            }
        }
    });
    std::vector<float>().swap(num_);
    std::vector<float>().swap(denom_);
    refPyramid_.clear();
    finished_ = true;
    return raw.data();
}

}  // namespace rawboy

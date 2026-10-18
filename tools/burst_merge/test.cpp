// Host test of engine/src/main/cpp/burst_merge.cpp on synthetic bursts: a continuous scene sampled with sub-pixel
// hand shake, Poisson-Gaussian noise, a moving square and clipped highlights. Run: tools/burst_merge/run.sh
#include "burst_merge.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <random>
#include <vector>

using rawboy::BurstMerger;

namespace {

constexpr int W = 1024, H = 768;
constexpr float BLACK = 64.0f, WHITE = 1023.0f;
// Noise in units of the white-black range (about a phone sensor at ISO ~800).
constexpr float NOISE_S = 4e-4f, NOISE_O = 4e-6f;

/** Scene light at a raw position (0..1 of the range, before the CFA's channel gains). */
float scene(double x, double y) {
    double v = 0.18 + 0.08 * std::sin(x * 0.05) * std::cos(y * 0.037) + 0.04 * std::sin((x + 2 * y) * 0.21);
    if (x > 600 && x < 900 && y > 80 && y < 300) v = 0.5 + 0.2 * std::sin(x * 0.4);  // fine stripes
    if ((x - 300) * (x - 300) + (y - 550) * (y - 550) < 60 * 60) v = 1.2;             // a clipped light
    if (y > 400 && y < 420) v = 0.05;                                                    // a dark bar
    return static_cast<float>(v);
}

const float CHANNEL[4] = {0.5f, 1.0f, 1.0f, 0.6f};  // RGGB response

struct Square { double x, y; };
constexpr double SQ = 60;  // moving square size

float clean(double sx, double sy, int x, int y, const Square* sq) {
    const double px = x + sx, py = y + sy;
    float v = scene(px, py);
    if (sq && px >= sq->x && px < sq->x + SQ && py >= sq->y && py < sq->y + SQ) v = 0.7f;
    return v * CHANNEL[(y & 1) * 2 + (x & 1)];
}

std::vector<uint16_t> frame(double sx, double sy, const Square* sq, std::mt19937& rng) {
    std::vector<uint16_t> f(W * H);
    std::normal_distribution<float> g(0.0f, 1.0f);
    for (int y = 0; y < H; ++y) {
        for (int x = 0; x < W; ++x) {
            const float s = std::min(1.0f, clean(sx, sy, x, y, sq));
            const float v = s + g(rng) * std::sqrt(NOISE_S * s + NOISE_O);
            f[y * W + x] = static_cast<uint16_t>(std::clamp(std::lround(BLACK + v * (WHITE - BLACK)), 0L, 1023L));
        }
    }
    return f;
}

/** RMS error of a frame against the clean reference, in DN, over a box (x0..x1, y0..y1). */
double rmse(const std::vector<uint16_t>& f, const Square* sq, int x0, int y0, int x1, int y1) {
    double s = 0;
    long n = 0;
    for (int y = y0; y < y1; ++y) {
        for (int x = x0; x < x1; ++x) {
            const double c = BLACK + std::min(1.0f, clean(0, 0, x, y, sq)) * (WHITE - BLACK);
            const double d = f[y * W + x] - c;
            s += d * d;
            ++n;
        }
    }
    return std::sqrt(s / n);
}

double mean(const std::vector<uint16_t>& f, int x0, int y0, int x1, int y1) {
    double s = 0;
    long n = 0;
    for (int y = y0; y < y1; y += 2)
        for (int x = x0; x < x1; x += 2) s += f[y * W + x + 1], ++n;  // G samples (even rows, odd columns)
    return s / n;
}

int failures = 0;
void check(bool ok, const char* what) {
    std::printf("%s %s\n", ok ? "ok  " : "FAIL", what);
    if (!ok) ++failures;
}

}  // namespace

int main() {
    std::mt19937 rng(7);
    std::uniform_real_distribution<double> shake(-6.0, 6.0);
    const float black[4] = {BLACK, BLACK, BLACK, BLACK};
    const int N = 8;

    // 1. Static scene, sub-pixel hand shake: noise down, no misregistration.
    {
        BurstMerger m(W, H, black, WHITE, NOISE_S, NOISE_O);
        std::vector<std::vector<uint16_t>> fs;
        for (int i = 0; i < N; ++i) {
            const double sx = i == 0 ? 0 : shake(rng), sy = i == 0 ? 0 : shake(rng);
            fs.push_back(frame(sx, sy, nullptr, rng));
            m.addFrame(reinterpret_cast<const uint8_t*>(fs.back().data()), W * 2, 1.0);
        }
        const uint16_t* merged = m.finish();
        std::vector<uint16_t> out(merged, merged + W * H);
        const double flatRef = rmse(fs[0], nullptr, 40, 40, 560, 380), flatOut = rmse(out, nullptr, 40, 40, 560, 380);
        const double stripeRef = rmse(fs[0], nullptr, 610, 90, 890, 290), stripeOut = rmse(out, nullptr, 610, 90, 890, 290);
        const double darkRef = rmse(fs[0], nullptr, 40, 402, 1000, 418), darkOut = rmse(out, nullptr, 40, 402, 1000, 418);
        std::printf("static: flat rmse %.2f -> %.2f DN (x%.2f), stripes %.2f -> %.2f, dark bar %.2f -> %.2f\n",
                    flatRef, flatOut, flatRef / flatOut, stripeRef, stripeOut, darkRef, darkOut);
        std::printf("  frame weights:");
        for (float w : m.frameWeights()) std::printf(" %.2f", w);
        std::printf("\n");
        check(flatRef / flatOut > 1.8, "static: flat noise at least 1.8x lower");
        check(stripeOut < stripeRef, "static: fine stripes not worse than the reference");
        check(darkRef / darkOut > 1.5, "static: dark bar noise at least 1.5x lower");
        // The light is at the clip, so with noise about half its samples read the white level.
        const double lightRef = mean(fs[0], 270, 520, 330, 580), lightOut = mean(out, 270, 520, 330, 580);
        std::printf("  clipped light: reference %.1f DN, merged %.1f DN\n", lightRef, lightOut);
        check(lightOut > lightRef - 2, "static: clipped light not darkened");
    }

    // 2. A square moving across the frame: the merge must not ghost it.
    {
        BurstMerger m(W, H, black, WHITE, NOISE_S, NOISE_O);
        std::vector<std::vector<uint16_t>> fs;
        const Square refSq{100, 120};
        for (int i = 0; i < N; ++i) {
            const Square sq{100.0 + i * 50.0, 120.0 + i * 10.0};
            const double sx = i == 0 ? 0 : shake(rng) * 0.3, sy = i == 0 ? 0 : shake(rng) * 0.3;
            fs.push_back(frame(sx, sy, &sq, rng));
            m.addFrame(reinterpret_cast<const uint8_t*>(fs.back().data()), W * 2, 1.0);
        }
        const uint16_t* merged = m.finish();
        std::vector<uint16_t> out(merged, merged + W * H);
        // Where the square is in the reference: it must stay; where only other frames had it: background.
        const double inRef = mean(out, 110, 130, 150, 170);
        const double expected = BLACK + 0.7 * (WHITE - BLACK);
        // Square of frame 4 (x 300..360, y 160..220), clear of the reference's square.
        const double ghostArea = rmse(out, &refSq, 305, 165, 355, 215), ghostRef = rmse(fs[0], &refSq, 305, 165, 355, 215);
        std::printf("moving: square in reference %.1f DN (expected %.1f), ghost-area rmse %.2f (reference alone %.2f)\n",
                    inRef, expected, ghostArea, ghostRef);
        check(std::abs(inRef - expected) < 8, "moving: reference's square kept");
        check(ghostArea < ghostRef * 1.5, "moving: no ghost of the square elsewhere");
    }

    // 3. Exposure normalisation: half the frames at half the exposure.
    {
        BurstMerger m(W, H, black, WHITE, NOISE_S, NOISE_O);
        std::vector<std::vector<uint16_t>> fs;
        for (int i = 0; i < 4; ++i) {
            std::vector<uint16_t> f = frame(0, 0, nullptr, rng);
            const double gain = (i % 2) ? 0.5 : 1.0;
            if (gain != 1.0) for (auto& v : f) v = static_cast<uint16_t>(std::lround(BLACK + (v - BLACK) * gain));
            fs.push_back(f);
            m.addFrame(reinterpret_cast<const uint8_t*>(fs.back().data()), W * 2, gain);
        }
        const uint16_t* merged = m.finish();
        std::vector<uint16_t> out(merged, merged + W * H);
        const double a = mean(fs[0], 40, 40, 560, 380), b = mean(out, 40, 40, 560, 380);
        std::printf("exposure: reference mean %.2f, merged %.2f\n", a, b);
        check(std::abs(a - b) < 1.0, "exposure: frames scaled to the reference's exposure");
    }

    // 4. Unknown noise model: estimated from the frames.
    {
        BurstMerger m(W, H, black, WHITE, 0, 0);
        std::vector<std::vector<uint16_t>> fs;
        for (int i = 0; i < N; ++i) {
            fs.push_back(frame(i == 0 ? 0 : shake(rng), i == 0 ? 0 : shake(rng), nullptr, rng));
            m.addFrame(reinterpret_cast<const uint8_t*>(fs.back().data()), W * 2, 1.0);
        }
        const uint16_t* merged = m.finish();
        std::vector<uint16_t> out(merged, merged + W * H);
        const double r = rmse(fs[0], nullptr, 40, 40, 560, 380) / rmse(out, nullptr, 40, 40, 560, 380);
        std::printf("estimated noise: flat x%.2f\n", r);
        check(r > 1.5, "estimated noise: flat noise at least 1.5x lower");
    }

    // 5. As the camera feeds it: levels and exposures arrive after the frames (results lag the images).
    {
        const float staticBlack[4] = {60, 60, 60, 60};
        BurstMerger m(W, H, staticBlack, WHITE, 0, 0, /* levelsKnown */ false);
        std::vector<std::vector<uint16_t>> fs;
        for (int i = 0; i < N; ++i) {
            fs.push_back(frame(i == 0 ? 0 : shake(rng), i == 0 ? 0 : shake(rng), nullptr, rng));
            const int index = m.addFrame(reinterpret_cast<const uint8_t*>(fs.back().data()), W * 2, 0.0);
            if (i == 1) m.setLevels(black, NOISE_S, NOISE_O);  // the reference's result comes in late
            if (i >= 1) m.setGain(index - 1, 1.0);             // each result a frame behind
        }
        // The last frame's exposure never arrives: finish() takes the reference's.
        const uint16_t* merged = m.finish();
        std::vector<uint16_t> out(merged, merged + W * H);
        const double r = rmse(fs[0], nullptr, 40, 40, 560, 380) / rmse(out, nullptr, 40, 40, 560, 380);
        const double a = mean(fs[0], 40, 40, 560, 380), b = mean(out, 40, 40, 560, 380);
        std::printf("streamed: flat x%.2f, mean %.2f -> %.2f, %zu frame weights, at most %d frames waiting\n", r, a, b,
                    m.frameWeights().size(), m.maxQueued());
        check(r > 1.8 && std::abs(a - b) < 1.0 && m.frameWeights().size() == N - 1,
              "streamed: late levels and exposures, all frames merged");
    }

    std::printf(failures ? "%d FAILED\n" : "all passed\n", failures);
    return failures ? 1 : 0;
}

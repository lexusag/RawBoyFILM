#pragma once

// Burst merge for RawBoy Camera: several Bayer raw frames of one scene -> one cleaner Bayer frame, written as a DNG
// by the caller (DngCreator). Platform-neutral (host-tested, see tools/burst_merge/); JNI in jni_bridge.cpp.
//
// The method follows Hasinoff et al., "Burst photography for high dynamic range and low-light imaging on mobile
// cameras" (SIGGRAPH Asia 2016), reimplemented here and simplified:
// - align: each frame against the first (the reference) on a 2x2-averaged grey copy, coarse to fine over a 4-level
//   pyramid, one integer displacement per tile. Displacements are whole grey pixels = even raw pixels, so the Bayer
//   phase is kept and no resampling is needed.
// - merge: overlapping 32 px raw tiles (stride 16, raised-cosine windows that sum to 1). Per tile and frame, a weight
//   from how much the aligned tile differs from the reference beyond what noise explains, times a per-pixel weight
//   from the local difference, so moving things fall back to the reference instead of ghosting. HDR+ merges in the
//   frequency domain; this is the spatial-domain counterpart.
//
// Streaming: a worker thread aligns and merges each frame into running sums as soon as it can (its exposure and the
// reference's levels known) and frees it, so memory holds the reference, the sums and only the frames still waiting,
// not the whole burst.

#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

namespace rawboy {

class BurstMerger {
public:
    /**
     * [black]: per position of the 2x2 CFA block, row-major (SENSOR_DYNAMIC_BLACK_LEVEL order). [white]: clip level.
     * [noiseScale]/[noiseOffset]: the sensor's noise model in units of the white-black range, variance =
     * scale * signal + offset (SENSOR_NOISE_PROFILE, averaged over the channels); <= 0 if unknown (then estimated).
     * [levelsKnown] false: frames wait for [setLevels] (the reference's own levels) before they are merged.
     */
    BurstMerger(int width, int height, const float black[4], float white, float noiseScale, float noiseOffset,
                bool levelsKnown = true);
    ~BurstMerger();
    BurstMerger(const BurstMerger&) = delete;
    BurstMerger& operator=(const BurstMerger&) = delete;

    /**
     * Copies one frame (16-bit samples, [rowStride] bytes per row) and returns its index; the first is the reference.
     * [gain]: its exposure (time x ISO), or <= 0 if not known yet ([setGain]); frames are scaled to the reference's.
     */
    int addFrame(const uint8_t* data, int rowStride, double gain);

    /** A frame's exposure (time x ISO), once known; the frame is merged after that. */
    void setGain(int frame, double gain);

    /** The black levels (null: keep the constructor's) and noise model; frames are merged only after this. */
    void setLevels(const float* black, float noiseScale, float noiseOffset);

    /**
     * Ends the burst: merges the frames still waiting (unknown exposures taken as the reference's) and returns
     * width x height 16-bit samples, valid until the merger is destroyed. Null without frames.
     */
    const uint16_t* finish();

    int frames() const;

    /** Per merged frame after the reference (in order): mean tile weight 0..1, for the log. */
    std::vector<float> frameWeights() const;

    /** Most frames waiting at once (copied but not yet merged), for the log. */
    int maxQueued() const;

    // Tuning, exposed for the host test (set before adding frames).
    /** Tile weight: 1 / (1 + excess / (tileK x noise variance)); lower rejects misaligned tiles harder. */
    float tileK = 4.0f;
    /** Pixel weight: rejects where the 3x3 mean grey difference exceeds ~pixelSigmas x its noise. */
    float pixelSigmas = 3.0f;

    static constexpr int MERGE_TILE = 32;              // raw px
    static constexpr int MERGE_STRIDE = MERGE_TILE / 2;
    static constexpr int GREY_TILE = MERGE_TILE / 2;   // the merge tile in grey px
    static constexpr int GREY_STRIDE = GREY_TILE / 2;

    struct Plane {
        int w = 0, h = 0;
        std::vector<float> px;
        float at(int x, int y) const {
            x = x < 0 ? 0 : (x >= w ? w - 1 : x);
            y = y < 0 ? 0 : (y >= h ? h - 1 : y);
            return px[static_cast<size_t>(y) * w + x];
        }
    };
    struct Shift { int dx = 0, dy = 0; };

private:
    struct Frame {
        std::vector<uint16_t> raw;  // freed once merged (kept for the reference)
        double gain = 0.0;          // <= 0: not known yet
        bool merged = false;
    };

    void run();
    void startReference(Frame& f);
    void mergeFrame(Frame& f, size_t index);
    Plane grey(const std::vector<uint16_t>& raw, double gain) const;
    std::vector<Shift> align(const std::vector<Plane>& alt) const;
    float greyVar(float level) const;

    const int width_, height_;
    float black_[4];
    const float white_;
    float noiseScale_, noiseOffset_;
    int tilesX_, tilesY_;

    // Guarded by mutex_: the queue state the camera thread and the worker share.
    mutable std::mutex mutex_;
    std::condition_variable changed_;
    std::vector<std::unique_ptr<Frame>> frames_;
    bool levelsKnown_;
    bool finishing_ = false;
    size_t next_ = 0;  // next frame to merge, in order
    int queued_ = 0, maxQueued_ = 0;
    std::vector<float> frameWeights_;

    // Worker only, until finish() joins it.
    Frame* ref_ = nullptr;  // frames_[0], taken when the worker starts on it
    std::vector<Plane> refPyramid_;
    float flatVar_ = 0.0f;  // estimated grey noise variance when the model is unknown
    std::vector<float> num_;    // per raw pixel: sum of window x weight x (sample - black)
    std::vector<float> denom_;  // per grey pixel (2x2 block): sum of window x weight
    double refGain_ = 1.0;
    bool finished_ = false;  // finish() ran; the reference's raw now holds the result
    std::thread worker_;
};

}  // namespace rawboy

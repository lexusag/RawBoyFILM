#pragma once

// Platform-neutral export helpers: the streaming area downscaler (BandDownscaler.kt is the Kotlin side)
// and the tile repack. Used by band_ops.cpp (JNI) and the web build's wasm bridge.

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace rawboy {

/** Exact area-average downscaler, streamed row by row; see BandDownscaler.kt for the contract. */
class Downscaler {
public:
    Downscaler(int inW, int inH, int outW, int outH)
        : inW_(inW), inH_(inH), outW_(outW), outH_(outH),
          sx_(static_cast<double>(inW) / outW), sy_(static_cast<double>(inH) / outH),
          tapStart_(outW), tapCount_(outW), tapOffset_(outW),
          row_(static_cast<size_t>(outW) * 3), acc_(row_.size()), accNext_(row_.size()) {
        for (int x = 0; x < outW; ++x) {
            const double a = x * sx_;
            const double b = std::min(static_cast<double>(inW), (x + 1) * sx_);
            const int first = static_cast<int>(std::floor(a));
            const int last = std::min(inW, static_cast<int>(std::ceil(b)));
            tapStart_[x] = first;
            tapOffset_[x] = static_cast<int>(tapWeights_.size());
            int count = 0;
            for (int ix = first; ix < last; ++ix) {
                const double coverage = std::min(b, ix + 1.0) - std::max(a, static_cast<double>(ix));
                if (coverage <= 0.0) continue;
                if (count == 0) tapStart_[x] = ix;
                tapWeights_.push_back(static_cast<float>(coverage / sx_));
                ++count;
            }
            tapCount_[x] = count;
        }
    }

    /** Adds [rows] input rows; finished output rows are written to [out] from its start. Returns their count. */
    int push(const uint16_t* in, int rows, uint16_t* out) {
        int written = 0;
        for (int r = 0; r < rows; ++r) written += pushRow(in + static_cast<size_t>(r) * inW_ * 3, out + static_cast<size_t>(written) * outW_ * 3);
        return written;
    }

    /** Writes the remaining output rows to [out]; returns their count. */
    int finish(uint16_t* out) {
        int written = 0;
        while (accRow_ < outH_) {
            finishRow(out + static_cast<size_t>(written) * outW_ * 3);
            ++written;
        }
        return written;
    }

    /** Most output rows one push of [inputRows] rows can produce. */
    int maxOutputRows(int inputRows) const { return static_cast<int>(std::ceil(inputRows / sy_)) + 2; }

private:
    int pushRow(const uint16_t* in, uint16_t* out) {
        if (inRow_ >= inH_) return 0;
        for (int x = 0; x < outW_; ++x) {
            float r = 0, g = 0, b = 0;
            const uint16_t* p = in + static_cast<size_t>(tapStart_[x]) * 3;
            const float* w = tapWeights_.data() + tapOffset_[x];
            for (int t = 0; t < tapCount_[x]; ++t, p += 3) {
                const float k = w[t];
                r += k * p[0];
                g += k * p[1];
                b += k * p[2];
            }
            row_[x * 3] = r;
            row_[x * 3 + 1] = g;
            row_[x * 3 + 2] = b;
        }
        const double top = inRow_;
        const double bottom = top + 1.0;
        for (int oy = accRow_; oy < outH_ && oy * sy_ < bottom; ++oy) {
            const double coverage = (std::min(bottom, (oy + 1) * sy_) - std::max(top, oy * sy_)) / sy_;
            if (coverage <= 0.0) continue;
            std::vector<float>& target = oy == accRow_ ? acc_ : accNext_;
            const float k = static_cast<float>(coverage);
            for (size_t i = 0; i < row_.size(); ++i) target[i] += k * row_[i];
        }
        ++inRow_;
        int written = 0;
        while (accRow_ < outH_ && (accRow_ + 1) * sy_ <= inRow_ + 1e-6) {
            finishRow(out + static_cast<size_t>(written) * outW_ * 3);
            ++written;
        }
        return written;
    }

    void finishRow(uint16_t* out) {
        for (size_t i = 0; i < acc_.size(); ++i) {
            // Same rounding as Kotlin's roundToInt (half up).
            const float v = std::floor(acc_[i] + 0.5f);
            out[i] = static_cast<uint16_t>(std::clamp(v, 0.0f, 65535.0f));
        }
        ++accRow_;
        std::swap(acc_, accNext_);
        std::fill(accNext_.begin(), accNext_.end(), 0.0f);
    }

    int inW_, inH_, outW_, outH_;
    double sx_, sy_;
    std::vector<int> tapStart_, tapCount_, tapOffset_;
    std::vector<float> tapWeights_;
    std::vector<float> row_, acc_, accNext_;
    int accRow_ = 0;
    int inRow_ = 0;
};

/**
 * Copies a developed tile ([cols] x [rows] RGBA samples in 0..65535, read back from the GPU as uint16 or uint32,
 * [bytesPerSample] 2 or 4) into a band of interleaved RGB 16-bit samples [bandWidth] pixels wide, from column [x].
 */
template <typename T>
void pack(const T* src, int cols, int rows, uint16_t* dst, int bandWidth, int x) {
    for (int r = 0; r < rows; ++r) {
        const T* s = src + static_cast<size_t>(r) * cols * 4;
        uint16_t* d = dst + (static_cast<size_t>(r) * bandWidth + x) * 3;
        for (int c = 0; c < cols; ++c, s += 4, d += 3) {
            d[0] = static_cast<uint16_t>(s[0]);
            d[1] = static_cast<uint16_t>(s[1]);
            d[2] = static_cast<uint16_t>(s[2]);
        }
    }
}

}  // namespace rawboy

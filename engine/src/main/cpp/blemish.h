#pragma once

#include "rawboy.h"

#include <vector>

namespace rawboy {

/** A detected spot in full-resolution (display-oriented) pixels; higher score = more obvious. */
struct Blemish {
    float x, y, radius, score;
};

/** The skin probability map (whole image, display orientation). */
struct SkinMaskView {
    const float* data;
    int width, height;
};

std::vector<Blemish> detectBlemishes(const DecodedImage& img, const SkinMaskView& mask, float faceDiameter);

/** Heals one spot in place, recording the original pixels in [backups]. */
void healBlemish(DecodedImage& img, const Blemish& spot, std::vector<HealBackup>& backups);

/** Undoes all healing recorded in [backups]. */
void restoreBackups(DecodedImage& img, std::vector<HealBackup>& backups);

}  // namespace rawboy

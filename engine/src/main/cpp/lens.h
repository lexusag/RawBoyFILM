#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace rawboy {

// DNG opcodes (DNG spec 1.4+, chapter 7), in raw/sensor coordinates.

/**
 * GainMap (opcode 9): a gain grid, e.g. phone lens shading. In OpcodeList2 (Pixel) one map per CFA site; in
 * OpcodeList3 (iPhone Bayer DNGs) one map for the demosaiced colour planes, with a gain plane per colour.
 */
struct GainMap {
    uint32_t top = 0, left = 0, bottom = 0, right = 0;
    uint32_t plane = 0, planes = 1, rowPitch = 1, colPitch = 1;
    uint32_t pointsV = 0, pointsH = 0, mapPlanes = 1;
    double spacingV = 0, spacingH = 0, originV = 0, originH = 0;
    std::vector<float> gains;  // pointsV * pointsH * mapPlanes, row-major
    /** The opcode list it came from: 2 (raw CFA data) or 3 (demosaiced planes). */
    int list = 2;

    /** Bilinear gain at relative image position (0..1, 0..1) in map plane [mapPlane]. */
    float at(double rowFract, double colFract, uint32_t mapPlane = 0) const;
};

/** WarpRectilinear (opcode 1, OpcodeList3): radial + tangential distortion per plane. */
struct WarpRectilinear {
    uint32_t planes = 0;
    double coef[3][6] = {};  // kr0..kr3, kt0, kt1
    double centerX = 0.5, centerY = 0.5;  // relative to image
};

/** FixVignetteRadial (opcode 3, OpcodeList3): gain = 1 + k0 r^2 + ... + k4 r^10. */
struct VignetteRadial {
    double k[5] = {};
    double centerX = 0.5, centerY = 0.5;
};

struct LensOpcodes {
    std::vector<GainMap> gainMaps;
    bool hasWarp = false;
    WarpRectilinear warp;
    bool hasVignette = false;
    VignetteRadial vignette;
};

/** A luminance gain grid in the same layout as GainMap, for the GPU. */
struct ShadingGrid {
    uint32_t pointsV = 0, pointsH = 0;
    double spacingV = 0, spacingH = 0, originV = 0, originH = 0;
    std::vector<float> gains;
    bool empty() const { return gains.empty(); }
};

/** Reads OpcodeList2/3 from the main raw IFD of a DNG. Returns false if none. */
bool parseDngOpcodes(const uint8_t* data, size_t size, LensOpcodes& out);

}  // namespace rawboy

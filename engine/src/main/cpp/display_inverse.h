#pragma once

// Display-referred images (JPEG, HEIC) back to the scene-linear light RawBoy's default develop renders them from,
// so a JPEG opened with no edits develops into itself and every slider, look and film stock works on it as on a raw.

namespace rawboy {

/**
 * Inverse of develop() in shaders/develop.glsl at default settings (AgX, Exposure 0 i.e. BASE_EXPOSURE_EV, contrast
 * 1, no levels) for a display source (uDisplaySource 1: AgX per channel, without its inset/outset matrices, and no
 * soft gamut compression; with them, saturated sRGB colours would need negative light). Constants are copied from
 * the shader: keep them in step (as tools/film_luts/generate.py does).
 */
class DisplayInverse {
public:
    /** The shared instance (its tables are built once). */
    static const DisplayInverse& get();

    /**
     * Display-linear sRGB (Rec.709 primaries, 0..1 = black..white; values outside come from wider-gamut files) ->
     * scene-linear Rec.2020, as the decoders store it.
     */
    void toScene(const float display709[3], float scene2020[3]) const;

    /** The scene value (per channel) that renders as display white. */
    float sceneWhite() const;

    /** The forward develop at defaults (scene-linear Rec.2020 -> display-linear sRGB), for tests and tools. */
    void toDisplay(const float scene2020[3], float display709[3]) const;

private:
    DisplayInverse();
    float inverseSigmoid(float y) const;
    static constexpr int kTableSize = 8192;
    float table_[kTableSize + 1];  // inverse AgX sigmoid sampled uniformly over its output range
    float yMin_, yMax_;
};

/** sRGB transfer curve, encoded -> linear (0..1, extended symmetrically beyond). */
float srgbToLinear(float v);

}  // namespace rawboy

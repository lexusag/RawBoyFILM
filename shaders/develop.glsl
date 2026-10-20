
in vec2 vUv;
uniform sampler2D uImage;
uniform mat3 uWhiteBalance;
uniform float uExposure;    // linear gain
uniform float uContrast;    // slope in AgX log space, 1 = neutral
uniform float uSaturation;  // 1 = neutral
uniform float uGamutSoft;   // 1 = purity-limited saturation + soft Rec.709 gamut compression, 0 = hard clipping
uniform int uToneMapper;    // 0 = AgX, 1 = filmic, 2 = sigmoid
uniform float uDisplaySource; // 1 = a JPEG/HEIC decoded by display_inverse.cpp: AgX per channel, no gamut compression
uniform float uShadows;     // -1..1
uniform float uHighlights;  // -1..1
uniform float uBlacks;      // -1..1
uniform float uWhites;      // -1..1
uniform float uDenoise;     // 0..1: luma denoise
uniform float uDenoiseWide; // 0..1: adds the coarse (multi-scale) luma denoise stage, for high ISO
uniform float uColorDenoise;     // 0..1: chroma denoise
uniform float uColorDenoiseWide; // 0..1: its coarse stage
uniform float uGrain;       // 0..1
uniform float uGrainSize;   // grain cell size in full-resolution pixels
uniform float uGrainRoughness; // 0..1: grain strength varies in clumps (a coarser field modulates it)
uniform float uGrainColor;  // 0 = monochrome grain, 1 = independent per channel
uniform float uSharpen;     // 0..1
uniform float uLocalContrast; // -1..1, edge-aware mid-frequency detail
uniform float uClarity;     // -1..1, high-pass (local) contrast
uniform sampler2D uBase;    // whole image, ~512 px: the large blur for uClarity
uniform vec2 uBaseTexel;    // 1 / base size
uniform vec2 uImageSize;    // full-resolution image size (display orientation)
uniform float uBloom;            // scattered fraction p, 0 = off
uniform sampler2D uBloomHalo;    // highlight-boosted ~512 px copy
uniform sampler2D uBloomTail;    // highlight-boosted ~128 px copy
uniform vec2 uBloomHaloTexel;
uniform vec2 uBloomTailTexel;
uniform float uRetouch;          // 0..1
uniform sampler2D uSkinMask;     // 0..1 skin probability over the whole image
uniform float uSkinSigma;        // smoothing sigma, full-resolution px (scaled to face size)
uniform highp sampler3D uLut;
uniform float uLutSize;     // nodes per axis
uniform float uLutAmount;   // 0 = off, 1 = full LUT
#define CURVE_SIZE 33
uniform float uCurveOn;             // 0 = off: no point curves
uniform float uClipWarn;            // preview only, 1 = clipped highlights red, crushed shadows blue
uniform vec3 uCurve[CURVE_SIZE];    // point curves (RGB, then per channel) sampled at i / (CURVE_SIZE - 1)
// Film image formation, assembled after this file: film_form.glsl (standard) or film/film.glsl (film edition).
uniform float uFilmAmount;  // 0 = off (AgX only) .. 1 = film only
vec3 applyFilm(vec3 c, vec2 fullPx, float contrast);

// Mask layers (see develop/Masks.kt): analytic gradients in image pixels, each with its own adjustments.
// Every uniform is "off" at 0, so programs that don't bind them render as before.
#define MAX_LAYERS 4
uniform int uLayerCount;
uniform vec4 uMaskGeom[MAX_LAYERS];   // linear: p0.xy, (p1 - p0) / |p1 - p0|^2; radial: centre.xy, 1/rx, 1/ry
uniform vec4 uMaskShape[MAX_LAYERS];  // linear: 0, curvature (1/px^2, over the length); radial: 1, cos(angle), sin(angle), feather 0..1; brush only: 2
uniform vec4 uLayerTone[MAX_LAYERS];  // exposure EV, log2 contrast slope, highlights, shadows
uniform vec4 uLayerColor[MAX_LAYERS]; // saturation factor, clarity, opacity, invert
uniform mat3 uLayerWb[MAX_LAYERS];    // global target white -> layer white
uniform int uMaskView;                // 0 = off, else layer index + 1 shown as a red overlay
uniform sampler2D uBrushMask;         // painted masks, RG (paint, erase), a 2x2 atlas: one slot per layer
uniform vec4 uBrushSlot[MAX_LAYERS];  // slot origin.xy and size.zw in atlas uv; size 0 = nothing painted
uniform vec2 uBrushTexel;             // 1 / atlas size

// Sampling geometry, so spatial effects are sized in full-resolution pixels
// in both the downscaled preview and the full-resolution export tiles.
uniform vec2 uTexel;        // 1 / texture size
uniform float uPixelScale;  // texture pixels per full-resolution pixel (1 for export)
uniform vec2 uInOrigin;     // full-resolution pixel position of texture uv (0,0)
uniform vec2 uInSpan;       // full-resolution pixels covered by the texture
uniform vec2 uOutOrigin;    // full-resolution output pixel at vUv (0,0)
uniform vec2 uOutSpan;      // full-resolution output pixels covered by the quad

// Lens (see LensTransform.kt, which mirrors this math for tiling and auto scale).
uniform mat3 uOutToImage;        // output px (rotated/straightened) -> image px
uniform mat3 uToSensor;          // display-oriented px -> sensor px
uniform mat3 uFromSensor;
uniform vec2 uImageCenter;       // display-oriented px
uniform float uHalfDiagonal;
uniform float uZoom;             // auto crop/scale
uniform float uManualK;          // manual distortion
uniform float uWarpOn;
uniform float uWarpPlanes;
uniform float uWarpK[18];        // per plane: kr0..kr3, kt0, kt1
uniform vec2 uWarpCenter;        // sensor px
uniform float uWarpNorm;
uniform float uVigOn;
uniform float uVigK[5];
uniform vec2 uVigCenter;         // sensor px
uniform float uVigNorm;
uniform float uShadingUndo;      // 1 = put back the luminance shading the decoder removed
uniform sampler2D uShading;      // DNG gain map (luminance), sensor space
uniform vec4 uShadingGrid;       // originV, originH, spacingV, spacingH
uniform vec2 uShadingPoints;     // pointsV, pointsH
uniform vec2 uSensorSize;
uniform float uManualVig;        // -1..1, + brightens corners
uniform float uManualVigMid;     // 0..1, higher = confined to the corners

const vec3 LUMA_REC2020 = vec3(0.2627, 0.6780, 0.0593);
const float MIDDLE_GREY_EV = -2.4739311883324122; // log2(0.18)

const mat3 AGX_INSET = mat3(
    vec3(0.856627153315983, 0.137318972929847, 0.11189821299995),
    vec3(0.0951212405381588, 0.761241990602591, 0.0767994186031903),
    vec3(0.0482516061458583, 0.101439036467562, 0.811302368396859));
const mat3 AGX_OUTSET = mat3(
    vec3(1.1271005818144368, -0.1413297634984383, -0.14132976349843826),
    vec3(-0.11060664309660323, 1.157823702216272, -0.11060664309660294),
    vec3(-0.016493938717834573, -0.016493938717834257, 1.2519364065950405));
const mat3 REC2020_TO_REC709 = mat3(
    vec3(1.6604910021, -0.1245504745, -0.0181507634),
    vec3(-0.5876411388, 1.1328998971, -0.1005788980),
    vec3(-0.0728498633, -0.0083494226, 1.1187296614));

const float AGX_MIN_EV = -12.47393; // middle grey - 10 EV
const float AGX_MAX_EV = 4.026069;  // middle grey + 6.5 EV
const float AGX_PIVOT = (MIDDLE_GREY_EV - AGX_MIN_EV) / (AGX_MAX_EV - AGX_MIN_EV);

vec3 agxSigmoid(vec3 x) {
    vec3 x2 = x * x;
    vec3 x4 = x2 * x2;
    return 15.5 * x4 * x2 - 40.14 * x4 * x + 31.96 * x4
         - 6.868 * x2 * x + 0.4298 * x2 + 0.1191 * x - 0.00232;
}

// Filmic and sigmoid share AgX's log range, middle grey (display 0.4967) and slope there (2.057 per
// unit of the log range), so Exposure, Contrast, grain and Whites/Blacks mean the same for all three.
// Both output the same display encoding as AgX (2.2), for the shared levels and gamut steps after it.
const float TONE_GREY = 0.4967306;
const float TONE_SLOPE = 2.057398;

// Filmic (after darktable's filmic rgb, reimplemented): a straight latitude through middle grey,
// rational toe and shoulder down to black and up to white, applied to max RGB with the channel
// ratios kept, so the curve doesn't shift hues.
const float FILMIC_TOE_X = AGX_PIVOT - 0.08;      // latitude: ~1.3 EV each side of middle grey
const float FILMIC_SHOULDER_X = AGX_PIVOT + 0.08;
const float FILMIC_TOE_Y = TONE_GREY - TONE_SLOPE * 0.08;
const float FILMIC_SHOULDER_Y = TONE_GREY + TONE_SLOPE * 0.08;
const float FILMIC_TOE_B = TONE_SLOPE * FILMIC_TOE_X / FILMIC_TOE_Y - 1.0;
const float FILMIC_SHOULDER_B = TONE_SLOPE * (1.0 - FILMIC_SHOULDER_X) / (1.0 - FILMIC_SHOULDER_Y) - 1.0;

float filmicCurve(float x) {
    if (x < FILMIC_TOE_X) {
        float u = x / FILMIC_TOE_X;
        return FILMIC_TOE_Y * u / (1.0 + FILMIC_TOE_B * (1.0 - u));
    }
    if (x > FILMIC_SHOULDER_X) {
        float v = (1.0 - x) / (1.0 - FILMIC_SHOULDER_X);
        return 1.0 - (1.0 - FILMIC_SHOULDER_Y) * v / (1.0 + FILMIC_SHOULDER_B * (1.0 - v));
    }
    return TONE_GREY + TONE_SLOPE * (x - AGX_PIVOT);
}

// x: log-encoded channels (0..1 over the AgX range).
vec3 filmicTone(vec3 x) {
    float n = max(max(x.r, x.g), x.b); // max RGB; log2 keeps the order, so it is the max of x
    vec3 ratio = exp2((x - n) * (AGX_MAX_EV - AGX_MIN_EV)); // linear channel / max, 0..1
    // Ratios alone keep a bright light saturated up to display white; fade them to white
    // across the shoulder, as film does.
    float w = clamp((n - FILMIC_SHOULDER_X) / (1.0 - FILMIC_SHOULDER_X), 0.0, 1.0);
    ratio = mix(ratio, vec3(1.0), w * w);
    return filmicCurve(n) * pow(ratio, vec3(1.0 / 2.2));
}

// Sigmoid (after darktable's sigmoid, reimplemented): a log-logistic curve, i.e. a logistic in EV,
// scaled so the ends of the log range land on black and white. Per channel, which desaturates
// highlights on its own; the middle channel is then put back where it was between the lowest and
// highest, or per-channel curves bend hues (orange towards yellow, blue towards cyan).
const float SIGMOID_STEEPNESS = 11.46293;
const float SIGMOID_MID = 0.7237407;
const float SIGMOID_LO = 1.0 / (1.0 + exp(SIGMOID_STEEPNESS * SIGMOID_MID));
const float SIGMOID_HI = 1.0 / (1.0 + exp(-SIGMOID_STEEPNESS * (1.0 - SIGMOID_MID)));

vec3 sigmoidTone(vec3 x) {
    vec3 y = (1.0 / (1.0 + exp(-SIGMOID_STEEPNESS * (x - SIGMOID_MID))) - SIGMOID_LO) / (SIGMOID_HI - SIGMOID_LO);
    y = clamp(y, 0.0, 1.0); // display linear
    vec3 l = exp2((x - 1.0) * (AGX_MAX_EV - AGX_MIN_EV)); // scene linear, relative
    float lMin = min(min(l.r, l.g), l.b);
    float lMax = max(max(l.r, l.g), l.b);
    if (lMax - lMin > 1e-6 * lMax) {
        float yMin = min(min(y.r, y.g), y.b);
        float yMax = max(max(y.r, y.g), y.b);
        y = yMin + (l - lMin) / (lMax - lMin) * (yMax - yMin);
    }
    return pow(y, vec3(1.0 / 2.2));
}

vec3 srgbEncode(vec3 c) {
    return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c));
}

// Saturation with purity as the mask (Saulala's "Sat vs Sat"): a boost can push the lowest
// channel below 0, which clipped pure colours flat. The extra saturation a pixel can take
// before that happens (its headroom, ~1/purity - 1) limits the boost with a soft knee at 80%,
// so neutrals and moderate colours get all of it, already pure colours little.
vec3 saturateByPurity(vec3 c, float s) {
    float y = dot(c, LUMA_REC2020);
    if (s > 1.0 && uGamutSoft > 0.0) {
        float m = min(min(c.r, c.g), c.b);
        float headroom = max(y / max(y - m, 1e-10) - 1.0, 0.0);
        float a = s - 1.0;
        float knee = 0.8 * headroom;
        if (a > knee) {
            float r = max(headroom - knee, 1e-10);
            a = knee + r * (1.0 - exp(-(a - knee) / r));
        }
        s = mix(s, 1.0 + a, uGamutSoft);
    }
    return max(mix(vec3(y), c, s), 0.0);
}

// Soft gamut compression into Rec.709 (the ACES reference gamut compression's method):
// each channel's distance from the achromatic axis, (max - c) / max, is > 1 where it is
// negative. Distances past a threshold are compressed so the furthest AgX can reach
// (2020 greens -> 1.58 in red, magentas 1.07, yellows 1.11) lands on the gamut edge
// instead of being clipped per channel, which shifted hues and flattened gradients.
const vec3 GAMUT_THRESHOLD = vec3(0.8, 0.85, 0.85);
const vec3 GAMUT_LIMIT = vec3(1.6, 1.1, 1.15);
const float GAMUT_POWER = 1.2;

vec3 compressGamut(vec3 c) {
    float ach = max(max(c.r, c.g), c.b);
    if (ach <= 0.0) return c;
    vec3 d = (ach - c) / ach;
    vec3 scale = (GAMUT_LIMIT - GAMUT_THRESHOLD)
        / pow(pow((1.0 - GAMUT_THRESHOLD) / (GAMUT_LIMIT - GAMUT_THRESHOLD), vec3(-GAMUT_POWER)) - 1.0, vec3(1.0 / GAMUT_POWER));
    vec3 n = max(d - GAMUT_THRESHOLD, 0.0) / scale;
    vec3 dc = GAMUT_THRESHOLD + scale * n / pow(1.0 + pow(n, vec3(GAMUT_POWER)), vec3(1.0 / GAMUT_POWER));
    d = mix(d, dc, step(GAMUT_THRESHOLD, d) * uGamutSoft);
    return ach - d * ach;
}

// Integer hashes seeded by full-resolution position (denoise taps, grain), so preview and export tiles match.
uvec3 pcg3d(uvec3 v) {
    v = v * 1664525u + 1013904223u;
    v.x += v.y * v.z; v.y += v.z * v.x; v.z += v.x * v.y;
    v ^= v >> 16u;
    v.x += v.y * v.z; v.y += v.z * v.x; v.z += v.x * v.y;
    return v;
}

vec3 unitHash(ivec2 cell, uint seed) {
    uvec3 h = pcg3d(uvec3(uvec2(cell + 32768), seed));
    return vec3(h >> 8u) * (1.0 / 16777216.0); // [0, 1)
}

// Edge-aware denoise on scene-linear data. Shot noise has roughly constant
// std in sqrt space, so filtering there treats shadows and highlights alike.
// Luma (uDenoise): 5x5 bilateral. Chroma (uColorDenoise; blotchy, low frequency): sparse 7x7, radius 9 px.
// At high strength a coarse stage follows (uDenoiseWide, uColorDenoiseWide): luma 5x5 at stride 3 (radius 6 px),
// chroma 7x7 at stride 6 (radius 18 px), for the low-frequency grain and colour blotches the fine stage can't reach.
// Its range weights compare against the fine result, so edges the fine stage kept stay.
// Each part has its own strength; with both equal this is the single denoise of before.
// The sparse stages don't sample a fixed lattice: every tap sits at a random point of its own cell (stratified,
// seeded by the full-resolution pixel), read with bilinear filtering. On a fixed lattice at stride 6 neighbouring
// pixels 6 px apart averaged almost the same samples, so the leftover colour noise took a 6 px grid pattern
// (blocky blotches on high-ISO camera raws); jittered, what's left is fine and uncorrelated. Cells are shrunk so
// the outermost tap still lands within the old radius (margins unchanged), and spatial weights use the real offset.
vec3 sqrtSample(vec2 uv) {
    return sqrt(max(texture(uImage, uv).rgb, 0.0));
}

// Offset in full-res pixels of tap (i, j) of an n-ring sparse stage with the given radius: a random point in its cell.
// The centre tap stays on the pixel itself, so the fine stages' weight sums never drop below 1.
vec2 jitteredTap(int i, int j, int n, float radius, ivec2 pixel, uint stage) {
    if (i == 0 && j == 0) return vec2(0.0);
    float cell = radius / (float(n) + 0.5);
    vec3 r = unitHash(pixel, stage + uint((j + n) * 16 + i + n));
    return (vec2(i, j) + r.xy - 0.5) * cell;
}

vec3 denoise(vec2 uv, vec2 fullPx, vec3 rgb) {
    vec3 c = sqrt(max(rgb, 0.0));
    float cl = (c.r + c.g + c.b) / 3.0;
    vec3 cc = c - cl;

    // Assumed noise std in sqrt space at full strength (~ISO 6400 on a typical
    // sensor). The downscaled preview averages pixels, which lowers the noise.
    float sigmaL = max(uDenoise * 0.03 * uPixelScale, 1e-5);
    float sigmaC = max(uColorDenoise * 0.03 * uPixelScale, 1e-5);
    float invLuma = 1.0 / (8.0 * sigmaL * sigmaL);    // range h = 2 sigma
    float invLumaC = 1.0 / (8.0 * sigmaC * sigmaC);   // the chroma filter's luma edge guard
    float invChroma = 1.0 / (18.0 * sigmaC * sigmaC); // range h = 3 sigma
    vec2 px = uTexel * uPixelScale;                    // one full-res pixel in uv
    ivec2 pix = ivec2(floor(fullPx));

    float fineL = cl;
    if (uDenoise > 0.0) {
        float lSum = 0.0;
        float lW = 0.0;
        for (int j = -2; j <= 2; ++j) {
            for (int i = -2; i <= 2; ++i) {
                vec3 s = sqrtSample(uv + vec2(i, j) * px);
                float l = (s.r + s.g + s.b) / 3.0;
                float d = l - cl;
                float w = exp(-float(i * i + j * j) / 4.5 - d * d * invLuma);
                lSum += l * w;
                lW += w;
            }
        }
        fineL = lSum / lW;
    }

    vec3 fineC = cc;
    if (uColorDenoise > 0.0) {
        vec3 cSum = vec3(0.0);
        float cW = 0.0;
        for (int j = -3; j <= 3; ++j) {
            for (int i = -3; i <= 3; ++i) {
                vec2 o = jitteredTap(i, j, 3, 9.0, pix, 0x51ed00u);
                vec3 s = sqrtSample(uv + o * px);
                float l = (s.r + s.g + s.b) / 3.0;
                vec3 dc = (s - l) - cc;
                float dl = l - cl;
                float w = exp(-dot(o, o) / 72.0 - dl * dl * invLumaC * 0.5 - dot(dc, dc) * invChroma);
                cSum += (s - l) * w;
                cW += w;
            }
        }
        fineC = cSum / cW;
    }

    if (uDenoiseWide > 0.0) {
        float wlSum = 0.0;
        float wlW = 0.0;
        for (int j = -2; j <= 2; ++j) {
            for (int i = -2; i <= 2; ++i) {
                vec2 o = jitteredTap(i, j, 2, 6.0, pix, 0x2c1b00u);
                vec3 s = sqrtSample(uv + o * px);
                float l = (s.r + s.g + s.b) / 3.0;
                float d = l - fineL;
                float w = exp(-dot(o, o) / 40.5 - d * d * invLuma);
                wlSum += l * w;
                wlW += w;
            }
        }
        fineL = mix(fineL, wlSum / wlW, uDenoiseWide);
    }
    if (uColorDenoiseWide > 0.0) {
        vec3 wcSum = vec3(0.0);
        float wcW = 0.0;
        for (int j = -3; j <= 3; ++j) {
            for (int i = -3; i <= 3; ++i) {
                vec2 o = jitteredTap(i, j, 3, 18.0, pix, 0x9a3f00u);
                vec3 s = sqrtSample(uv + o * px);
                float l = (s.r + s.g + s.b) / 3.0;
                vec3 dc = (s - l) - fineC;
                float dl = l - fineL;
                float w = exp(-dot(o, o) / 288.0 - dl * dl * invLumaC * 0.5 - dot(dc, dc) * invChroma);
                wcSum += (s - l) * w;
                wcW += w;
            }
        }
        // The coarse stages are for blotches in even areas: their leftover is faint next to the slider's noise level.
        // A small feature with its own colour (an iris, a catchlight's rim) differs from its surroundings by much
        // more, and averaging it with them turned blue-grey eyes brown. So each coarse stage moves the colour by its
        // full amount only while the change is small (gate ~0.35 sigma, flat-topped), and leaves larger changes alone.
        vec3 wc = wcSum / wcW - fineC;
        float invGate = 1.0 / (2.0 * 0.35 * 0.35 * sigmaC * sigmaC);
        float gw = dot(wc, wc) * invGate;
        fineC += wc * (uColorDenoiseWide * exp(-gw * gw));

        // Coarsest colour scale, from the 512 px base copy (~16 px per texel on a 50 MP raw, ~8 on a phone): colour
        // blotches 20-100 px across outlive the 18 px stage, and raising the exposure turns them into blotchy
        // patches. 7x7 texels at stride 1 (a sparser stride drew a 2-texel grid), Gaussian sigma ~1.8 texels.
        // Range-weighted against the result so far, with tighter luma and colour guards than the other stages
        // (the blotches are faint), so colour edges and small features (eyes, lips) stay. Skipped without a base
        // copy (uBaseTexel 1).
        vec2 buv = fullPx / uImageSize;
        vec3 bSum = vec3(0.0);
        float bW = 0.0;
        for (int j = -3; j <= 3 && uBaseTexel.x < 0.5; ++j) {
            for (int i = -3; i <= 3; ++i) {
                vec3 s = sqrt(max(texture(uBase, buv + vec2(i, j) * uBaseTexel).rgb, 0.0));
                float l = (s.r + s.g + s.b) / 3.0;
                vec3 dc = (s - l) - fineC;
                float dl = l - fineL;
                float w = exp(-float(i * i + j * j) * 0.16 - dl * dl * invLumaC - dot(dc, dc) * invChroma * 4.0);
                bSum += (s - l) * w;
                bW += w;
            }
        }
        if (bW > 1e-4) {
            vec3 bc = bSum / bW - fineC;
            float gb = dot(bc, bc) * invGate;
            fineC += bc * (uColorDenoiseWide * exp(-gb * gb));
        }
    }
    vec3 outSqrt = max(vec3(fineL) + fineC, 0.0);
    return outSqrt * outSqrt;
}

// Film-like grain, added in the log (density-like) domain as spektrafilm does.
// Sparse convolution noise: one randomly placed soft Gaussian "grain" per cell,
// cells sized relative to the image (like a film frame), mostly luminance with
// some per-layer colour. Seeded by full-resolution position so tiles match.
// One field of soft grains on cells of `size` full-resolution pixels, ~unit variance per channel.
vec3 grainField(vec2 fullPx, float size, uint seed) {
    vec2 q = fullPx / size;
    vec2 base = floor(q);
    vec3 sum = vec3(0.0);
    float w2 = 0.0;
    for (int j = -2; j <= 2; ++j) {
        for (int i = -2; i <= 2; ++i) {
            vec2 cell = base + vec2(i, j);
            ivec2 id = ivec2(cell);
            vec2 d = q - (cell + unitHash(id, 0x9e37u + seed).xy); // jittered grain centre
            float w = exp(-dot(d, d) * 2.0);                         // Gaussian, sigma = 0.5 cell
            sum += w * (unitHash(id, 0x2545u + seed) * 2.0 - 1.0);
            w2 += w * w;
        }
    }
    return sum * inversesqrt(max(w2, 1e-6) / 3.0); // ~unit variance everywhere
}

vec3 grainNoise(vec2 fullPx) {
    vec3 g = grainField(fullPx, uGrainSize, 0u);
    if (uGrainRoughness > 0.0) {
        // Uneven grain: grains twice as large mixed in, as clumps among the fine ones. Same variance, so Amount
        // keeps its meaning.
        float w = uGrainRoughness * 1.5;
        g = (g + w * grainField(fullPx, uGrainSize * 2.0, 0x51edu)) * inversesqrt(1.0 + w * w);
    }
    float mono = (g.r + g.g + g.b) * 0.57735;
    // Each channel's variance dips between mono and full colour (they are correlated); keep it at the default 0.15's.
    float k = uGrainColor;
    return mix(vec3(mono), g, k) * sqrt(0.89222 / ((1.0 - k) * (1.0 - k) + k * k + 1.1547 * k * (1.0 - k)));
}

// 3D LUT on the final sRGB-encoded image, sampled at node centres.
// Colour film LUTs tone even a grey image (up to ~19/255), so near Saturation -100
// (the last quarter of the slider) their colour fades out too: -100 is black and white.
vec3 applyLut(vec3 c) {
    vec3 coord = clamp(c, 0.0, 1.0) * ((uLutSize - 1.0) / uLutSize) + 0.5 / uLutSize;
    c = mix(c, texture(uLut, coord).rgb, uLutAmount);
    float keep = clamp(uSaturation * 4.0, 0.0, 1.0);
    return mix(vec3(dot(c, vec3(0.2126, 0.7152, 0.0722))), c, keep);
}

// Point curves (Curves in Pro) on the final sRGB-encoded image, after the LUT: each channel interpolates
// its column of the uCurve table (ShaderParams' curve table, already through the RGB curve).
vec3 applyCurves(vec3 c) {
    vec3 t = clamp(c, 0.0, 1.0) * float(CURVE_SIZE - 1);
    ivec3 i = min(ivec3(t), ivec3(CURVE_SIZE - 2));
    vec3 f = t - vec3(i);
    return vec3(
        mix(uCurve[i.r].r, uCurve[i.r + 1].r, f.r),
        mix(uCurve[i.g].g, uCurve[i.g + 1].g, f.g),
        mix(uCurve[i.b].b, uCurve[i.b + 1].b, f.b));
}

// Clipping warning (Pro's histogram, preview only): a channel at 8-bit 255 shows red, every channel at 1 or below
// blue, the same pixels the histogram counts (Histogram.kt).
vec3 clipWarning(vec3 c) {
    if (max(c.r, max(c.g, c.b)) >= 254.5 / 255.0) return vec3(1.0, 0.0, 0.0);
    if (max(c.r, max(c.g, c.b)) <= 1.5 / 255.0) return vec3(0.0, 0.35, 1.0);
    return c;
}

float lum(vec3 c) { return dot(c, LUMA_REC2020); }

// Unsharp mask on luminance as a log ratio (multiplicative, so no dark halos around
// highlights): fine detail = small blur (sigma 0.7 px) over a wider one (2 px).
// Differences below a noise threshold are ignored so noise isn't sharpened.
float sharpenGain(vec2 uv) {
    vec2 px = uTexel * uPixelScale;
    float ys = 0.0, ws = 0.0, yb = 0.0, wb = 0.0;
    for (int j = -2; j <= 2; ++j) {
        for (int i = -2; i <= 2; ++i) {
            vec2 o = vec2(i, j);
            float l = lum(texture(uImage, uv + o * px).rgb);
            float r2 = dot(o, o);
            float w1 = exp(-r2 / 0.98);
            float w2 = exp(-r2 / 8.0);
            ys += w1 * l; ws += w1;
            yb += w2 * l; wb += w2;
        }
    }
    float d = log2(max(ys / ws, 1e-6)) - log2(max(yb / wb, 1e-6));
    float threshold = 0.008 + uDenoise * 0.04;
    d = sign(d) * max(abs(d) - threshold, 0.0);
    return exp2(uSharpen * 3.5 * clamp(d, -0.4, 0.4));
}

// Local contrast: mid-frequency detail (texture a few pixels across, between sharpening and
// high-pass contrast) as a log-luminance ratio against an edge-aware blur, ~4.5 px sigma over
// 6 px. Neighbours more than about an EV away from the centre count little, so strong edges
// keep no halo; large ratios are compressed, so the boost goes to fine texture, not edges.
// Very dark and very bright tones are left alone (shadow noise, clipped highlights).
float localContrastGain(vec2 uv, float amount) {
    vec2 px = uTexel * uPixelScale * 3.0;    // taps 3 full-resolution pixels apart
    float y0 = log2(max(lum(texture(uImage, uv).rgb), 1e-6));
    float sum = 0.0, wsum = 0.0;
    for (int j = -2; j <= 2; ++j) {
        for (int i = -2; i <= 2; ++i) {
            float l = log2(max(lum(texture(uImage, uv + vec2(i, j) * px).rgb), 1e-6));
            float dl = l - y0;
            float w = exp(-float(i * i + j * j) / 4.5 - dl * dl * 2.5);
            sum += w * l;
            wsum += w;
        }
    }
    float d = y0 - sum / wsum;
    float threshold = 0.006 + uDenoise * 0.03;
    d = sign(d) * max(abs(d) - threshold, 0.0);
    d /= 1.0 + 2.0 * abs(d);
    float ev = y0 + log2(uExposure) - MIDDLE_GREY_EV;
    float w = exp(-ev * ev / 40.0);
    // Negative amounts smooth the texture, at most flattening it (no inverted detail).
    return exp2((amount > 0.0 ? 1.6 : 1.0) * amount * d * w);
}

// High-pass contrast: luminance relative to a wide blur from the small base image.
float clarityGain(float y, vec2 imagePx, float amount) {
    vec2 uv = imagePx / uImageSize;
    float sum = 0.0, wsum = 0.0;
    for (int j = -2; j <= 2; ++j) {
        for (int i = -2; i <= 2; ++i) {
            float w = exp(-float(i * i + j * j) / 4.5);
            sum += w * lum(texture(uBase, uv + vec2(i, j) * 1.5 * uBaseTexel).rgb);
            wsum += w;
        }
    }
    float b = max(sum / wsum, 1e-6);
    float d = clamp(log2(max(y, 1e-6) / b), -2.0, 2.0);
    // Mostly midtones: fade out for very dark and very bright surroundings.
    float ev = log2(b * uExposure) - MIDDLE_GREY_EV;
    float w = exp(-ev * ev / 18.0);
    return exp2(amount * 0.7 * d * w);
}

// Skin retouch: edge-aware smoothing of blotches and redness at about 1% of the
// face size, with the fine texture (pores) multiplied back in so skin keeps its
// structure. Large steps in any channel (eyes, brows, lips) are left alone.
vec3 retouchSkin(vec2 uvG, vec2 imagePx, vec3 x) {
    float m = texture(uSkinMask, imagePx / uImageSize).r;
    if (m < 0.02) return x;
    vec2 px = uTexel * uPixelScale;           // one full-resolution pixel in uv
    vec3 lc = log2(max(x, 1e-5));

    // Fine blur (sigma ~1.2 px): what's above it is pore-scale texture to keep.
    vec3 fine = vec3(0.0);
    float fw = 0.0;
    for (int j = -1; j <= 1; ++j) {
        for (int i = -1; i <= 1; ++i) {
            float w = exp(-0.5 * float(i * i + j * j));
            fine += w * texture(uImage, uvG + vec2(i, j) * 1.2 * px).rgb;
            fw += w;
        }
    }
    fine /= fw;

    // Edge-aware (bilateral) smoothing on a sparse 7x7 grid.
    // The pixel itself is the centre tap (weight 1): where a near-black channel is noisy
    // (saturated yellows: blue ~ 0), every neighbour's log difference is huge, all other
    // weights underflow, and a 0 / 0 here rendered as black dots.
    float stride = uSkinSigma * 0.75;
    vec3 sum = x;
    float wsum = 1.0;
    for (int j = -3; j <= 3; ++j) {
        for (int i = -3; i <= 3; ++i) {
            if (i == 0 && j == 0) continue;
            vec3 s = texture(uImage, uvG + vec2(i, j) * stride * px).rgb;
            vec3 d = log2(max(s, 1e-5)) - lc;
            float w = exp(-float(i * i + j * j) * 0.28 - dot(d, d) * 15.4); // range sigma 0.18 (log2)
            sum += w * s;
            wsum += w;
        }
    }
    vec3 smoothed = sum / wsum;
    vec3 detail = clamp(x / max(fine, 1e-5), 0.5, 2.0);
    vec3 retouched = smoothed * mix(vec3(1.0), detail, 0.9);
    return mix(x, retouched, uRetouch * m);
}

// Pre-clip highlight reconstruction (spektrafilm's boost): light near and above
// clipping carries more energy into the glow. Must match the native downsample.
vec3 bloomBoost(vec3 c) {
    float t = clamp((max(c.r, max(c.g, c.b)) - 0.6) / 0.4, 0.0, 1.25);
    return c * (1.0 + 6.0 * t * t);
}

// Cubic B-spline sample of a small copy from four bilinear taps: smooth and close to round,
// where a bilinear sample of a coarse copy shows its texel grid.
vec3 textureBSpline(sampler2D t, vec2 uv, vec2 texel) {
    vec2 st = uv / texel - 0.5;
    vec2 i = floor(st);
    vec2 f = st - i;
    vec2 f2 = f * f;
    vec2 f3 = f2 * f;
    vec2 w0 = (1.0 - 3.0 * f + 3.0 * f2 - f3) / 6.0;
    vec2 w1 = (4.0 - 6.0 * f2 + 3.0 * f3) / 6.0;
    vec2 w3 = f3 / 6.0;
    vec2 g0 = w0 + w1;
    vec2 g1 = 1.0 - g0;
    vec2 h0 = (i - 0.5 + w1 / g0) * texel;
    vec2 h1 = (i + 1.5 + w3 / g1) * texel;
    return g0.y * (g0.x * texture(t, h0).rgb + g1.x * texture(t, vec2(h1.x, h0.y)).rgb)
         + g1.y * (g0.x * texture(t, vec2(h0.x, h1.y)).rgb + g1.x * texture(t, h1).rgb);
}

// Scattered light through a diffusion filter, after spektrafilm's PSF model: core 0.30 + halo
// 0.25 + tail 0.45, each normalised, so mixing it in conserves energy. The halo has a slightly
// warm outer rim. The kernels are round, their weights falling to zero at the rim: square tap
// grids with heavy corner weights drew square halos around bright windows.
vec3 bloomScatter(vec2 uvG, vec2 imagePx) {
    vec2 uv = imagePx / uImageSize;

    // Core: tight Gaussian, sigma ~0.04% of the long edge, from the full image.
    vec2 corePx = uTexel * uPixelScale * max(uImageSize.x, uImageSize.y) * 0.0004;
    vec3 core = vec3(0.0);
    float cw = 0.0;
    for (int j = -1; j <= 1; ++j) {
        for (int i = -1; i <= 1; ++i) {
            float w = exp(-0.5 * float(i * i + j * j));
            core += w * bloomBoost(texture(uImage, uvG + vec2(i, j) * corePx).rgb);
            cw += w;
        }
    }

    // Halo: Gaussian on the 512 px copy, sigma 1.2 texels (~0.25% of the long edge), out to 3.5 texels.
    vec3 halo = vec3(0.0);
    float hw = 0.0;
    for (int j = -3; j <= 3; ++j) {
        for (int i = -3; i <= 3; ++i) {
            float r2 = float(i * i + j * j);
            float rim = max(1.0 - r2 / 12.25, 0.0);
            float w = exp(-r2 / 2.88) * rim * rim;
            halo += w * texture(uBloomHalo, uv + vec2(i, j) * uBloomHaloTexel).rgb;
            hw += w;
        }
    }

    // Tail: the 64 px copy arrives already scattered (BloomTail: a round power law out to ~10% of the long
    // edge, dense next to the light like a mist filter, fading into a wide haze). One B-spline sample keeps it
    // smooth; summing taps per pixel from a bilinear copy showed the coarse texels as bands and squares.
    vec3 tail = textureBSpline(uBloomTail, uv, uBloomTailTexel);

    vec3 warmRim = vec3(1.024, 1.004, 0.968);
    return 0.30 * core / cw + 0.25 * warmRim * halo / hw + 0.45 * tail;
}

vec2 toSensor(vec2 p) { return (uToSensor * vec3(p, 1.0)).xy; }
vec2 fromSensor(vec2 s) { return (uFromSensor * vec3(s, 1.0)).xy; }

// Output pixel -> position after auto scale and manual distortion (display orientation).
vec2 lensPre(vec2 p) {
    vec2 d = (p - uImageCenter) / uZoom;
    float rn2 = dot(d, d) / (uHalfDiagonal * uHalfDiagonal);
    return uImageCenter + d * (1.0 - uManualK * rn2);
}

// DNG WarpRectilinear in sensor space for one plane.
vec2 warpPlane(vec2 s, int plane) {
    vec2 u = (s - uWarpCenter) / uWarpNorm;
    float r2 = dot(u, u);
    int o = plane * 6;
    float f = uWarpK[o] + r2 * (uWarpK[o + 1] + r2 * (uWarpK[o + 2] + r2 * uWarpK[o + 3]));
    vec2 t = vec2(uWarpK[o + 4] * 2.0 * u.x * u.y + uWarpK[o + 5] * (r2 + 2.0 * u.x * u.x),
                  uWarpK[o + 4] * (r2 + 2.0 * u.y * u.y) + uWarpK[o + 5] * 2.0 * u.x * u.y);
    return uWarpCenter + uWarpNorm * (u * f + t);
}

// Vignetting gains at a source position (sensor and display coordinates).
float lensGain(vec2 s, vec2 p) {
    float g = 1.0;
    if (uVigOn > 0.5) {
        vec2 u = (s - uVigCenter) / uVigNorm;
        float r2 = dot(u, u);
        g *= 1.0 + r2 * (uVigK[0] + r2 * (uVigK[1] + r2 * (uVigK[2] + r2 * (uVigK[3] + r2 * uVigK[4]))));
    }
    if (uShadingUndo > 0.5) {
        vec2 rel = s / uSensorSize;                                      // (h, v) fractions
        vec2 idx = (rel.yx - uShadingGrid.xy) / uShadingGrid.zw;         // (v, h) grid index
        vec2 tc = (idx.yx + 0.5) / uShadingPoints.yx;                    // texture (h, v)
        g /= max(texture(uShading, tc).r, 1e-3);
    }
    if (uManualVig != 0.0) {
        float rn = min(length(p - uImageCenter) / uHalfDiagonal, 1.5);
        g *= exp2(2.0 * uManualVig * pow(rn, mix(1.5, 8.0, uManualVigMid)));
    }
    return g;
}

// Weight 0..1 of mask layer i at an image pixel (display orientation, full resolution).
float layerMask(int i, vec2 p) {
    vec4 g = uMaskGeom[i];
    vec4 k = uMaskShape[i];
    float m = 0.0;
    if (k.x < 0.5) {
        vec2 d = p - g.xy;
        float t = dot(d, g.zw);
        if (k.y != 0.0) {
            float s = dot(d, normalize(vec2(-g.w, g.z)));  // distance across the gradient
            t += k.y * s * s;                              // bent lines: parabolas
        }
        m = 1.0 - smoothstep(0.0, 1.0, t);
    } else if (k.x < 1.5) {
        vec2 d = p - g.xy;
        vec2 r = vec2(k.y * d.x + k.z * d.y, -k.z * d.x + k.y * d.y) * g.zw;
        m = 1.0 - smoothstep(1.0 - k.w, 1.0, length(r));
    }
    vec4 b = uBrushSlot[i];
    if (b.z > 0.0) {
        // Painted strokes: erase takes away from the gradient, paint adds (max), in the order they were made.
        vec2 uv = clamp(p / uImageSize, 0.0, 1.0) * b.zw;
        vec2 pe = texture(uBrushMask, b.xy + clamp(uv, 0.5 * uBrushTexel, b.zw - 0.5 * uBrushTexel)).rg;
        m = max(m * (1.0 - pe.y), pe.x);
    }
    vec4 c = uLayerColor[i];
    if (c.w > 0.5) m = 1.0 - m;
    return m * c.z;
}

// Levels (Whites/Blacks) on the display-encoded signal.
vec3 applyLevels(vec3 c) {
    float inBlack = max(-uBlacks, 0.0) * 0.2;        // raise black point
    float outBlack = max(uBlacks, 0.0) * 0.15;       // lift / fade
    float inWhite = 1.0 - max(uWhites, 0.0) * 0.2;   // lower white point
    float outWhite = 1.0 - max(-uWhites, 0.0) * 0.15; // dim whites
    c = clamp((c - inBlack) / (inWhite - inBlack), 0.0, 1.0);
    return mix(vec3(outBlack), vec3(outWhite), c);
}

vec3 develop(vec3 c, vec2 fullPx, float lm[MAX_LAYERS]) {
    c = max(uWhiteBalance * c, 0.0) * uExposure;
    float layerEv = 0.0, layerContrast = 0.0, layerHighlights = 0.0, layerShadows = 0.0, layerSat = 1.0;
    for (int i = 0; i < uLayerCount; ++i) {
        float m = lm[i];
        if (m <= 0.0) continue;
        c = mix(c, max(uLayerWb[i] * c, 0.0), m);
        vec4 t = uLayerTone[i];
        layerEv += m * t.x;
        layerContrast += m * t.y;
        layerHighlights += m * t.z;
        layerShadows += m * t.w;
        layerSat *= mix(1.0, uLayerColor[i].x, m);
    }
    c *= exp2(layerEv);

    // Shadows/highlights: luminance gain weighted by distance from middle grey.
    // Max +-2 EV keeps the curve monotonic. Negative highlights pull the top
    // of the sensor range into AgX's shoulder to restore detail.
    float ev = log2(max(dot(c, LUMA_REC2020), 1e-8)) - MIDDLE_GREY_EV;
    float shadowWeight = 1.0 - smoothstep(-6.0, 0.0, ev);
    float highlightWeight = smoothstep(0.0, 4.0, ev);
    float shadows = clamp(uShadows + layerShadows, -1.0, 1.0);
    float highlights = clamp(uHighlights + layerHighlights, -1.0, 1.0);
    c *= exp2(2.0 * (shadows * shadowWeight + highlights * highlightWeight));

    c = saturateByPurity(c, uSaturation * layerSat);

    // Film negative + print, blended with the AgX render below by uFilmAmount (Strength).
    vec3 film = vec3(0.0);
    if (uFilmAmount > 0.0) {
        film = applyLevels(applyFilm(c, fullPx, uContrast * exp2(layerContrast)));
        if (uFilmAmount >= 1.0) return film;
    }

    // Tone mapping. All three log-encode over AgX's range, so grain and contrast work alike;
    // only AgX uses its inset/outset matrices, and not for a display source: its saturated colours lie outside what
    // they can render from non-negative light, so it is decoded for (and rendered with) AgX per channel.
    bool agxMatrices = uToneMapper == 0 && uDisplaySource < 0.5;
    if (agxMatrices) c = AGX_INSET * c;
    c = log2(max(c, 1e-10));
    c = clamp((c - AGX_MIN_EV) / (AGX_MAX_EV - AGX_MIN_EV), 0.0, 1.0);
    if (uGrain > 0.0) {
        // 0.015 of the log range ~= 0.25 EV std at full strength. Where one screen
        // pixel covers several grains (fit view), show their average instead of
        // aliased speckle.
        float footprint = max(max(fwidth(fullPx.x), fwidth(fullPx.y)), 1.0);
        c += grainNoise(fullPx) * (uGrain * 0.015 * min(1.0, uGrainSize / footprint));
    }
    c = clamp((c - AGX_PIVOT) * (uContrast * exp2(layerContrast)) + AGX_PIVOT, 0.0, 1.0);
    if (uToneMapper == 1) {
        c = filmicTone(c);
    } else if (uToneMapper == 2) {
        c = sigmoidTone(c);
    } else {
        c = agxSigmoid(c);
        if (agxMatrices) c = AGX_OUTSET * c;
    }

    c = applyLevels(c);
    c = pow(max(c, 0.0), vec3(2.2));                 // tone mapper's display encoding -> linear
    c = REC2020_TO_REC709 * c;
    c = clamp(uDisplaySource > 0.5 ? c : compressGamut(c), 0.0, 1.0);
    c = srgbEncode(c);
    if (uFilmAmount > 0.0) c = mix(c, film, uFilmAmount);
    return c;
}

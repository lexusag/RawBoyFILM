// SPDX-License-Identifier: GPL-3.0-or-later
// Film simulation after spektrafilm by Andrea Volpato (https://github.com/andreavolpato/spektrafilm, GPLv3,
// commit 500bc42). The per-pixel part (spectral exposure, density curves, DIR couplers, enlarger, paper, scan)
// is spektrafilm's own 2-LUT bake (tools/spektrafilm_luts); this file ports the spatial effects the LUTs can't hold:
//   filmOptics  <- spektrafilm/model/diffusion.py, apply_halation_um (in-emulsion scatter + back-reflection halation)
//   filmGrain   <- spektrafilm/model/grain.py, layer_particle_model / apply_grain_to_density (its statistics,
//                  as a Gaussian with the same mean and variance per pixel, then its grain blur)
// Spatial sizes are micrometres on a 36 mm wide frame, as spektrafilm's default 35 mm format.
// Assembled only when RAWBOY_FILM is defined (Android's Shaders.kt); every uniform is "off" at 0.

#ifdef RAWBOY_FILM
uniform highp sampler3D uFilmLut;   // Apple Log (Rec.2020) -> normalised film CMY density
uniform highp sampler3D uPrintLut;  // normalised film CMY density -> sRGB
uniform float uFilmLutSize;
uniform float uPrintLutSize;
uniform vec3 uFilmDMin;             // density wire: D = code * (dMax - dMin) + dMin, above base + fog
uniform vec3 uFilmDMax;
uniform float uFilmUmPerPx;         // film-plane micrometres per full-resolution pixel
uniform vec3 uHalation;             // back-reflection strength per channel (x amount); 0 = off
uniform float uHalationSigmaUm;     // first bounce; bounce k has sigma * sqrt(k)
uniform float uHalationDecay;       // weight of each further bounce
uniform vec3 uScatterTailUm;        // in-emulsion scatter: exponential decay length per channel
uniform vec3 uScatterWeight;        // its share of the light (x amount); 0 = off
uniform float uFilmGrain;           // grain amount, 1 = as simulated; 0 = off
uniform vec3 uGrainParticleUm2;     // silver halide particle area per layer
uniform vec3 uGrainDMin;            // spektrafilm grain density_min
uniform vec3 uGrainDMax;            // maximum density of each layer's curve
uniform vec3 uGrainUniformity;
uniform float uGrainBlurPx;         // grain blur sigma in pixels

// Apple Log Profile encoding (Apple Log Profile white paper; colour-science's constants).
vec3 appleLog(vec3 r) {
    vec3 lg = 0.08550479 * log2(max(r, 0.01) + 0.00964052) + 0.69336945;
    vec3 q = r + 0.05641088;
    vec3 sq = 47.28711236 * q * q;
    return mix(mix(vec3(0.0), sq, step(-0.05641088, r)), lg, step(0.01, r));
}

vec3 sampleCube(highp sampler3D lut, float size, vec3 c) {
    return texture(lut, clamp(c, 0.0, 1.0) * ((size - 1.0) / size) + 0.5 / size).rgb;
}

// Light spreading in and under the emulsion before it is exposed, on the decoded (linear) image.
// Scatter: an exponential kernel a pixel or two wide, from the full-resolution image; only the difference to the
// centre sample is added, so the denoised value isn't replaced. Halation: light reflected back from the film
// base, a sum of Gaussians (sigma 65 um x sqrt(k), weights decaying per bounce) over the whole-image copy, then
// renormalised (spektrafilm's halation_renormalize).
vec3 filmOptics(vec3 x, vec2 uvG, vec2 imagePx) {
    if (uFilmUmPerPx <= 0.0) return x;
    vec3 lambda = uScatterTailUm / uFilmUmPerPx * uPixelScale; // texture pixels
    if (any(greaterThan(uScatterWeight, vec3(0.0))) && max(lambda.r, max(lambda.g, lambda.b)) > 0.25) {
        vec3 centre = texture(uImage, uvG).rgb;
        vec3 sum = vec3(0.0);
        vec3 wsum = vec3(0.0);
        for (int j = -1; j <= 1; ++j) {
            for (int i = -1; i <= 1; ++i) {
                vec3 w = exp(-length(vec2(i, j)) / max(lambda, 1e-3));
                sum += w * texture(uImage, uvG + vec2(i, j) * uTexel).rgb;
                wsum += w;
            }
        }
        x += uScatterWeight * (sum / wsum - centre);
    }
    if (any(greaterThan(uHalation, vec3(0.0)))) {
        vec2 uv = imagePx / uImageSize;
        float basePx = uFilmUmPerPx * uImageSize.x * uBaseTexel.x;   // micrometres per base pixel
        float s1 = max(uHalationSigmaUm / basePx, 0.35);
        vec3 sigma2 = vec3(s1 * s1, 2.0 * s1 * s1, 3.0 * s1 * s1);    // bounces 1..3
        float spacing = max(sqrt(sigma2.z) * 0.9, 0.5);               // 7x7 taps reach ~2.7 sigma of the widest
        vec3 h1 = vec3(0.0), h2 = vec3(0.0), h3 = vec3(0.0);
        vec3 w = vec3(0.0);
        for (int j = -3; j <= 3; ++j) {
            for (int i = -3; i <= 3; ++i) {
                vec2 o = vec2(i, j) * spacing;
                vec3 g = exp(-0.5 * dot(o, o) / sigma2);
                vec3 b = texture(uBase, uv + o * uBaseTexel).rgb;
                h1 += g.x * b;
                h2 += g.y * b;
                h3 += g.z * b;
                w += g;
            }
        }
        float d2 = uHalationDecay;
        float d3 = d2 * d2;
        vec3 halo = (h1 / w.x + d2 * h2 / w.y + d3 * h3 / w.z) / (1.0 + d2 + d3);
        x = (x + uHalation * halo) / (1.0 + uHalation);
    }
    return x;
}

// spektrafilm's grain: per layer, n = pixel area / particle area developed particles of density dMax / n, each
// developed with probability D / dMax, the number of seeds saturating as density rises (uniformity). Its Poisson-
// binomial count has mean D and variance D * (dMax / n) * (1 - uniformity * D / dMax): that Gaussian, then the
// grain blur. Sized per output pixel, so a downscaled view averages grains as a smaller scan would; seeded by
// full-resolution position, so export tiles match.
vec3 filmGrain(vec3 code, vec2 fullPx) {
    vec3 span = uFilmDMax - uFilmDMin;
    vec3 density = code * span + uFilmDMin;
    float footprint = max(max(fwidth(fullPx.x), fwidth(fullPx.y)), 1.0);
    float pxUm = uFilmUmPerPx * footprint;
    vec3 n = max(pxUm * pxUm / uGrainParticleUm2, vec3(1.0));
    vec3 dMax = uGrainDMax + uGrainDMin;
    vec3 d = clamp(density + uGrainDMin, vec3(0.0), dMax);
    vec3 sigma = sqrt(max(d * (dMax / n) * (1.0 - uGrainUniformity * d / dMax), 0.0));

    vec2 cell = floor(fullPx / footprint);
    float b2 = 2.0 * max(uGrainBlurPx, 0.01) * max(uGrainBlurPx, 0.01);
    vec3 noise = vec3(0.0);
    float wsum = 0.0;
    for (int j = -1; j <= 1; ++j) {
        for (int i = -1; i <= 1; ++i) {
            float w = exp(-float(i * i + j * j) / b2);
            noise += w * (unitHash(ivec2(cell) + ivec2(i, j), 0x51edu) - 0.5) * 3.4641016; // unit variance
            wsum += w;
        }
    }
    density += noise / wsum * sigma * uFilmGrain;
    return (density - uFilmDMin) / span;
}

// Scene-linear Rec.2020 (middle grey 0.18, as spektrafilm's film expects) -> film -> print -> sRGB-encoded.
// Contrast scales log exposure around middle grey, as AgX's contrast does in its log space.
vec3 applyFilm(vec3 c, vec2 fullPx, float contrast) {
    float y = max(dot(c, LUMA_REC2020), 1e-8);
    c *= pow(y / 0.18, contrast - 1.0);
    vec3 code = sampleCube(uFilmLut, uFilmLutSize, appleLog(c));
    if (uFilmGrain > 0.0) code = filmGrain(code, fullPx);
    return sampleCube(uPrintLut, uPrintLutSize, code);
}
#endif

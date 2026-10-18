
// Film image formation for the standard apps (Auto, Pro, Camera, web): the negative -> print model of RawBoy Film,
// with RawBoy's own grain, halation and scatter. Assembled after develop.glsl in place of shaders/film/film.glsl
// (film edition only, GPLv3), with the same entry points: filmOptics() and applyFilm().
//
// Written from the physics, not from spektrafilm's code:
// - Formation: spektrafilm's baked 2-LUT bundles (luts/spektrafilm, CC BY-SA 4.0): Apple Log (BT.2020) in ->
//   normalised film CMY density -> sRGB print. Apple Log as published in Apple's "Apple Log Profile" white paper.
// - Grain: Selwyn's law (sigma_D * sqrt(area) is constant), scaled from the stock's RMS granularity (datasheet sigma
//   of density through a 48 um aperture) to the pixel's area on a 36 mm frame; per-layer density dependence from
//   binomial grain occupancy, sigma^2 ~ D (1 - D / Dsat) (the Boolean model, Newson et al. 2017).
// - Halation: light reflected at the back of the base returns at up to the critical angle, a disc of radius
//   ~2 t tan(theta_c) around its source (t = base thickness); reaches the red-sensitive layer (nearest the base) most.
// - Scatter: a short per-layer blur of light inside the emulsion.
// Sizes are micrometres on a 36 mm frame, so preview and export match at any resolution.

uniform highp sampler3D uFilmLut;   // Apple Log -> normalised CMY density
uniform highp sampler3D uPrintLut;  // normalised CMY density -> sRGB
uniform float uFilmLutSize;
uniform float uPrintLutSize;
uniform vec3 uFilmDMin;             // density wire: D = code * (max - min) + min
uniform vec3 uFilmDMax;
uniform float uFilmUmPerPx;         // micrometres of film per full-resolution pixel; 0 = no film
uniform float uFilmGrain;           // 0 = off, 1 = the stock's granularity
uniform vec3 uFormGrainRms;         // per layer (C, M, Y): RMS granularity at net density 1, 48 um aperture
uniform vec3 uFormHalation;         // per channel: fraction of light returned by the base (x amount); 0 = off
uniform float uFormHaloUm;          // halation disc radius
uniform vec3 uFormScatter;          // per channel: scattered fraction (x amount); 0 = off
uniform float uFormScatterUm;       // scatter distance

// Apple Log (Apple Log Profile white paper, 2023).
vec3 appleLogEncode(vec3 x) {
    const float R0 = -0.05641088, RT = 0.01, C = 47.28711236, B = 0.00964052, G = 0.08550479, BETA = 0.69336945;
    vec3 lo = C * (x - R0) * (x - R0);
    vec3 hi = G * log2(max(x, RT) + B) + BETA;
    return mix(mix(vec3(0.0), lo, step(vec3(R0), x)), hi, step(vec3(RT), x));
}

vec3 sampleNodes(highp sampler3D lut, float size, vec3 c) {
    return texture(lut, clamp(c, 0.0, 1.0) * ((size - 1.0) / size) + 0.5 / size).rgb;
}

// Halation and scatter on scene-linear light, before the lens gains (the film sees light after the lens).
vec3 filmOptics(vec3 x, vec2 uvG, vec2 imagePx) {
    if (uFilmUmPerPx <= 0.0) return x;
    // Scatter: four diagonal taps at the scatter distance (bilinear, so ~a small disc), as a difference to the
    // centre so denoise and the other effects already in x are kept.
    float rTex = uFormScatterUm / uFilmUmPerPx * uPixelScale;
    if (rTex > 0.25 && dot(uFormScatter, uFormScatter) > 0.0) {
        vec2 o = rTex * uTexel * 0.7071;
        vec3 centre = texture(uImage, uvG).rgb;
        vec3 ring = 0.25 * (texture(uImage, uvG + o).rgb + texture(uImage, uvG - o).rgb
                + texture(uImage, uvG + vec2(o.x, -o.y)).rgb + texture(uImage, uvG + vec2(-o.x, o.y)).rgb);
        x += uFormScatter * (ring - centre);
    }
    // Halation: the disc, as a Gaussian of half its radius, from a ~512 px copy (no copy: texel 1, skip). The
    // highlight-boosted bloom copy when there is one: light sources sit far above the sensor clip, and only they
    // halate visibly.
    bool boosted = uBloomHaloTexel.x < 1.0;
    vec2 texel = boosted ? uBloomHaloTexel : uBaseTexel;
    if (texel.x < 1.0 && dot(uFormHalation, uFormHalation) > 0.0) {
        float copyPerPx = 1.0 / (texel.x * uImageSize.x);
        float sigma = 0.5 * uFormHaloUm / uFilmUmPerPx * copyPerPx; // copy pixels
        vec2 uv = imagePx / uImageSize;
        vec3 halo = vec3(0.0);
        float wsum = 0.0;
        for (int j = -2; j <= 2; ++j) {
            for (int i = -2; i <= 2; ++i) {
                vec2 o = vec2(i, j) * 0.8;
                float w = exp(-0.5 * dot(o, o));
                vec2 at = uv + o * sigma * texel;
                halo += w * (boosted ? texture(uBloomHalo, at).rgb : texture(uBase, at).rgb);
                wsum += w;
            }
        }
        // The returned light is spread around its source: a mix keeps a uniform field unchanged.
        x = mix(x, halo / wsum, uFormHalation);
    }
    return x;
}

// Unit Gaussian per channel at a full-resolution pixel (Box-Muller on the shared pcg hash).
vec3 gaussPx(ivec2 p) {
    vec3 u1 = max(unitHash(p, 0x6b43u), 1e-7);
    vec3 u2 = unitHash(p, 0x1f35u);
    return sqrt(-2.0 * log(u1)) * cos(6.2831853 * u2);
}

// Grain in film density. Dye clouds are a little larger than a grain: a pixel and half its four neighbours.
vec3 formGrain(vec3 d, vec2 fullPx) {
    ivec2 p = ivec2(floor(fullPx));
    vec3 n = gaussPx(p) + 0.5 * (gaussPx(p + ivec2(1, 0)) + gaussPx(p - ivec2(1, 0))
            + gaussPx(p + ivec2(0, 1)) + gaussPx(p - ivec2(0, 1)));
    n *= 0.57735; // 1 / sqrt(1 + 4 * 0.25)
    // Selwyn: sigma at this pixel's area, from sigma through the 48 um aperture (area pi 24^2 um^2).
    float area = uFilmUmPerPx * uFilmUmPerPx;
    vec3 sigma = uFormGrainRms * sqrt(1809.56 / area);
    // Density dependence, normalised to 1 at net density 1 (where granularity is quoted).
    vec3 dSat = max(uFilmDMax, 1.2);
    vec3 dn = max(d, 0.0) + 0.05;
    vec3 occ = dn * max(1.0 - dn / dSat, 0.0) / (1.0 - 1.0 / dSat);
    // Where one output pixel covers several film pixels (fit view), show their average.
    float footprint = max(max(fwidth(fullPx.x), fwidth(fullPx.y)), 1.0);
    return n * sigma * sqrt(occ) * (uFilmGrain / footprint);
}

// Scene light (white balanced, exposed: 0.18 = middle grey) -> negative -> print, display-encoded sRGB.
vec3 applyFilm(vec3 c, vec2 fullPx, float contrast) {
    // Contrast as a slope in log exposure around middle grey, like the tone mappers'.
    float y = max(dot(c, LUMA_REC2020), 1e-8);
    c *= pow(y / 0.18, contrast - 1.0);
    vec3 span = uFilmDMax - uFilmDMin;
    vec3 d = sampleNodes(uFilmLut, uFilmLutSize, appleLogEncode(c)) * span + uFilmDMin;
    if (uFilmGrain > 0.0) d += formGrain(d, fullPx);
    return sampleNodes(uPrintLut, uPrintLutSize, (d - uFilmDMin) / span);
}

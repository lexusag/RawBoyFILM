
void main() {
    vec2 outPx = uOutOrigin + vUv * uOutSpan;
    vec2 sQ = toSensor(lensPre((uOutToImage * vec3(outPx, 1.0)).xy));
    bool perPlane = uWarpOn > 0.5 && uWarpPlanes > 2.5;
    vec2 sG = uWarpOn > 0.5 ? warpPlane(sQ, perPlane ? 1 : 0) : sQ;
    vec2 srcG = fromSensor(sG);
    vec2 uvG = (srcG - uInOrigin) / uInSpan;
    vec3 base = texture(uImage, uvG).rgb;
    vec3 x = base;
    if (uDenoise > 0.0 || uColorDenoise > 0.0) x = denoise(uvG, x);
    if (perPlane) {
        // Lateral chromatic aberration: shift R and B to their own warped positions.
        vec2 uvR = (fromSensor(warpPlane(sQ, 0)) - uInOrigin) / uInSpan;
        vec2 uvB = (fromSensor(warpPlane(sQ, 2)) - uInOrigin) / uInSpan;
        x.r += texture(uImage, uvR).r - base.r;
        x.b += texture(uImage, uvB).b - base.b;
    }
    // Local contrast and sharpening work on the image as decoded, before lens gains,
    // so the centre pixel and its neighbourhood are compared like for like.
    if (uRetouch > 0.0) x = retouchSkin(uvG, srcG, x);
    float g = 1.0;
    if (uSharpen > 0.0) g *= sharpenGain(uvG);
    if (uLocalContrast != 0.0) g *= localContrastGain(uvG, uLocalContrast);
    float lm[MAX_LAYERS];
    float clarity = uClarity;
    for (int i = 0; i < MAX_LAYERS; ++i) {
        lm[i] = i < uLayerCount ? layerMask(i, srcG) : 0.0;
        clarity += lm[i] * uLayerColor[i].y;
    }
    clarity = clamp(clarity, -1.0, 1.0);
    if (clarity != 0.0) g *= clarityGain(lum(x), srcG, clarity);
    x *= g;
    if (uBloom > 0.0) x = mix(x, bloomScatter(uvG, srcG), uBloom);
    if (uFilmAmount > 0.0) x = filmOptics(x, uvG, srcG);
    x *= lensGain(sG, srcG);
    vec3 c = develop(max(x, 0.0), outPx, lm);
    if (uMaskView > 0) c = mix(c, vec3(1.0, 0.0, 0.0), 0.5 * lm[uMaskView - 1]);

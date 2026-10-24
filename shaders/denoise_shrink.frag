#version 300 es
// Wavelet denoise pre-pass, step 3 (per level): shrinks the band and adds it to the sum. Hand-ported to Metal in
// Helpers.metal: keep the two in step.
//
// The shrink factor comes from the band's local energy (3x3 window at the band's own spacing), not from each
// coefficient alone: thresholding single coefficients let the few noise samples above the threshold survive while
// their neighbours were zeroed, which drew salt-and-pepper dots on flat areas. With the local energy, a lone noise
// peak shrinks like its neighbours and an edge (high energy around it) stays. Factor (1 - t^2 / E)+, a garrote on the
// energy. Chroma shrinks as a vector, so its hue is kept. A fraction of the band (uKeep) is kept regardless.
precision highp float;
precision highp int;
precision highp sampler2D;
uniform sampler2D uDetail;
uniform sampler2D uAcc;
uniform int uStride;
uniform int uFirst;
uniform vec3 uThr;      // luma threshold, chroma threshold (sqrt units, divided by L + k here; 0 = keep), k
uniform float uOffset;  // c of the sqrt(x + c) transform
uniform float uKeep;    // fraction of the luma band's original kept regardless (fine grain)
uniform float uKeepC;   // the same for chroma
out vec4 o;
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 hi = textureSize(uDetail, 0) - 1;
    vec4 d = texelFetch(uDetail, p, 0);
    float el = 0.0, ec = 0.0, w = 0.0;
    for (int j = -1; j <= 1; ++j) {
        for (int i = -1; i <= 1; ++i) {
            vec4 n = texelFetch(uDetail, clamp(p + ivec2(i, j) * uStride, ivec2(0), hi), 0);
            float wt = (i == 0 && j == 0) ? 2.0 : 1.0;
            el += wt * n.x * n.x;
            ec += wt * dot(n.yz, n.yz);
            w += wt;
        }
    }
    el /= w;
    ec /= w;
    // The thresholds are sqrt-domain noise; sqrt(x + c) is flatter than sqrt(x) near black by sqrt(x / (x + c)).
    // Following that fully would stop denoising at black, so it levels off at half.
    float x = max(d.w * d.w - uOffset, 0.0);
    float slope = sqrt((x + 0.25 * uOffset) / (x + uOffset));
    float tl = uThr.x * slope;
    float tc = uThr.y * slope / (max(d.w, 0.0) + uThr.z);
    float fl = max(1.0 - tl * tl / max(el, 1e-20), 0.0);
    float fc = max(1.0 - tc * tc / max(ec, 1e-20), 0.0);
    // Luma guard: where the band has luma structure (an edge, texture), chroma detail is kept as much as luma's.
    // Colour edges mostly sit on luma edges; and under strongly coloured light the dim channels' structure (all a
    // mono LUT may show: blue light, a mono render of red and green) counts as "chroma" here.
    fc = max(fc, fl);
    vec4 acc = uFirst == 1 ? vec4(0.0) : texelFetch(uAcc, p, 0);
    o = acc + vec4(d.x * mix(fl, 1.0, uKeep), d.yz * mix(fc, 1.0, uKeepC), 0.0);
}

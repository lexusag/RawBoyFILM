#version 300 es
// Wavelet denoise pre-pass, step 2 (per level): one B3-spline a trous step, the next low pass and this band's detail
// (current - next; w = the low pass's luma). Hand-ported to Metal in Helpers.metal: keep the two in step.
precision highp float;
precision highp int;
precision highp sampler2D;
uniform sampler2D uLow;
uniform int uStride;
layout(location = 0) out vec4 oLow;
layout(location = 1) out vec4 oDetail;
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 hi = textureSize(uLow, 0) - 1;
    float k[5] = float[](0.0625, 0.25, 0.375, 0.25, 0.0625);
    vec4 s = vec4(0.0);
    for (int j = 0; j < 5; ++j) {
        for (int i = 0; i < 5; ++i) {
            s += k[i] * k[j] * texelFetch(uLow, clamp(p + ivec2(i - 2, j - 2) * uStride, ivec2(0), hi), 0);
        }
    }
    oLow = s;
    oDetail = vec4(texelFetch(uLow, p, 0).xyz - s.xyz, s.x);
}

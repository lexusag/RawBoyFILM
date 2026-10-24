#version 300 es
// Wavelet denoise pre-pass, step 1 of 4 (see WaveletDenoise.kt): the image into luma and ratio chroma in the
// sqrt(x + c) domain. Hand-ported to Metal in ios/Engine/app/rawboy/metal/Helpers.metal: keep the two in step.
precision highp float;
precision highp int;
precision highp sampler2D;
uniform sampler2D uSrc;
uniform float uK;     // ratio chroma offset: keeps (c - L) / (L + k) finite near black
uniform float uOffset; // c of sqrt(x + c)
out vec4 o;
void main() {
    vec3 c = sqrt(max(texelFetch(uSrc, ivec2(gl_FragCoord.xy), 0).rgb + uOffset, 0.0));
    float l = (c.r + c.g + c.b) / 3.0;
    o = vec4(l, (c.r - l) / (l + uK), (c.b - l) / (l + uK), 0.0);
}

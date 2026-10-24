#version 300 es
// Wavelet denoise pre-pass, step 4: low pass + shrunk bands, back to linear RGB. Hand-ported to Metal in
// Helpers.metal: keep the two in step.
precision highp float;
precision highp int;
precision highp sampler2D;
uniform sampler2D uLow;
uniform sampler2D uAcc;
uniform float uK;
uniform float uOffset;
out vec4 o;
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec4 v = texelFetch(uLow, p, 0) + texelFetch(uAcc, p, 0);
    float m = max(v.x, 0.0) + uK;
    vec3 c = max(vec3(v.x + v.y * m, v.x - (v.y + v.z) * m, v.x + v.z * m), 0.0);
    o = vec4(max(c * c - uOffset, 0.0), 1.0);
}

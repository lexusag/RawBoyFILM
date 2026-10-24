#version 300 es
// Full-target quad for the wavelet denoise passes (denoise_*.frag); they address texels by gl_FragCoord.
layout(location = 0) in vec2 aPos;
void main() { gl_Position = vec4(aPos, 0.0, 1.0); }

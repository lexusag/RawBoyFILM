#version 300 es
layout(location = 0) in vec2 aPos;
uniform vec2 uScale;
uniform float uFlipY;
uniform vec4 uUvRect; // xy = offset, zw = size of the texture region shown
out vec2 vUv;
void main() {
    vec2 uv = aPos * 0.5 + 0.5;
    vUv = uUvRect.xy + vec2(uv.x, mix(uv.y, 1.0 - uv.y, uFlipY)) * uUvRect.zw;
    gl_Position = vec4(aPos * uScale, 0.0, 1.0);
}

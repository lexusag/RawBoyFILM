// Small hand-written Metal programs next to the generated develop shader (ios/build/generated, from <repo>/shaders).
#include <metal_stdlib>
using namespace metal;

/** A full-target quad in one solid colour; the caller limits it with a scissor rect (the fit view's frame). */
vertex float4 solidVertex(uint vid [[vertex_id]]) {
    const float2 p[4] = {float2(-1, -1), float2(1, -1), float2(-1, 1), float2(1, 1)};
    return float4(p[vid], 0, 1);
}

fragment float4 solidFragment(constant float4& color [[buffer(0)]]) {
    return color;
}

// ---- Viewfinder passes (ViewfinderRenderer). Output row 0 = top of the upright image. ----

struct PassOut {
    float4 position [[position]];
    float2 uv;  // y = 0 at the top
};

vertex PassOut passVertex(uint vid [[vertex_id]]) {
    const float2 p[4] = {float2(-1, -1), float2(1, -1), float2(-1, 1), float2(1, 1)};
    PassOut o;
    o.position = float4(p[vid], 0, 1);
    o.uv = float2(p[vid].x * 0.5 + 0.5, 0.5 - p[vid].y * 0.5);
    return o;
}

struct Linearize {
    float3x3 color;  // linear sRGB -> Rec.2020 (columns)
    float scale;     // linearised preview -> raw units (1.0 = sensor clip)
    int rotation;    // clockwise quarter turns from buffer to upright
    float mirror;    // 1: show a mirror image (selfie camera)
    float toneCurve;   // 1: map luminance through kCameraCurve (iPhone video frames), 0: unchanged
    float saturation;  // power of each channel over luminance (1 = unchanged)
};

/**
 * The iPhone's video frame luminance -> the raw's, log2, at frame log2 -8, -7.5, ... 0 (1.0 = the frame's clip),
 * 0.18 kept at 0.18. Medians over ProRAW shots (viewfinder copy at the shutter vs the raw; ViewfinderRenderer.Frame):
 * the video lifts shadows a little and squeezes everything above ~0.3 into its last stop and a half.
 */
constant float kCameraCurve[17] = {-6.679, -6.389, -6.071, -5.692, -5.304, -4.947, -4.566, -4.164, -3.800,
                                   -3.442, -3.016, -2.502, -1.959, -1.409, -0.782, -0.136, 0.556};

static float cameraCurve(float y) {
    float t = clamp((log2(y) + 8.0) * 2.0, 0.0, 16.0);
    int i = min(int(t), 15);
    float l = mix(kCameraCurve[i], kCameraCurve[i + 1], t - float(i));
    // Below the table, the first segment's slope.
    float below = min(log2(y) + 8.0, 0.0) * (kCameraCurve[1] - kCameraCurve[0]) * 2.0;
    return exp2(l + below);
}

/**
 * Camera frame (BGRA, sRGB-encoded, in the sensor's own landscape orientation) -> upright scene-linear Rec.2020.
 * Rotated and mirrored here, as on Android: AVFoundation ignored the selfie connection's rotation after a switch.
 */
fragment float4 linearizeFragment(PassOut in [[stage_in]], texture2d<float> camera [[texture(0)]],
                                  sampler s [[sampler(0)]], constant Linearize& p [[buffer(0)]]) {
    float u = mix(in.uv.x, 1.0 - in.uv.x, p.mirror);
    float v = in.uv.y;
    // Buffer position (y = 0 at the buffer's top) that lands at (u, v) after rotating clockwise.
    float2 b;
    if (p.rotation == 1) b = float2(v, 1.0 - u);
    else if (p.rotation == 2) b = float2(1.0 - u, 1.0 - v);
    else if (p.rotation == 3) b = float2(1.0 - v, u);
    else b = float2(u, v);
    float3 c = clamp(camera.sample(s, b).rgb, 0.0, 1.0);
    float3 lin = mix(c / 12.92, pow((c + 0.055) / 1.055, float3(2.4)), step(float3(0.04045), c));
    float3 rec = max(p.color * lin, 1e-6);
    float y = dot(rec, float3(0.2627, 0.6780, 0.0593));
    float yOut = p.toneCurve > 0.5 ? cameraCurve(y) : y;
    rec = yOut * pow(rec / y, float3(p.saturation));
    return float4(rec * p.scale, 1.0);
}

/** Trilinear copy from the source's mipmaps (the ~512 px base). */
fragment float4 copyFragment(PassOut in [[stage_in]], texture2d<float> source [[texture(0)]], sampler s [[sampler(0)]]) {
    return float4(source.sample(s, in.uv).rgb, 1.0);
}

/** Same-size copy with the shader's bloomBoost (and the native downsample's). */
fragment float4 boostFragment(PassOut in [[stage_in]], texture2d<float> source [[texture(0)]], sampler s [[sampler(0)]]) {
    float3 c = max(source.sample(s, in.uv).rgb, 0.0);
    float t = clamp((max(c.r, max(c.g, c.b)) - 0.6) / 0.4, 0.0, 1.25);
    return float4(c * (1.0 + 6.0 * t * t), 1.0);
}

/** 8x box downscale: 4x4 bilinear taps, each the mean of 2x2 source texels. */
fragment float4 boxDownFragment(PassOut in [[stage_in]], texture2d<float> source [[texture(0)]], sampler s [[sampler(0)]],
                                constant float2& texel [[buffer(0)]]) {
    float3 sum = float3(0.0);
    for (int j = 0; j < 4; ++j) {
        for (int i = 0; i < 4; ++i) {
            sum += source.sample(s, in.uv + float2(float(2 * i - 3), float(2 * j - 3)) * texel).rgb;
        }
    }
    return float4(sum * 0.0625, 1.0);
}

/** Same-size pass: BloomTail's kernel (keep the two in step), edges clamped by the sampler. */
fragment float4 tailScatterFragment(PassOut in [[stage_in]], texture2d<float> source [[texture(0)]], sampler s [[sampler(0)]],
                                    constant float2& texel [[buffer(0)]]) {
    float3 sum = float3(0.0);
    float ws = 0.0;
    for (int j = -6; j <= 6; ++j) {
        for (int i = -6; i <= 6; ++i) {
            float r2 = float(i * i + j * j);
            float rim = 1.0 - r2 / 49.0;
            if (rim <= 0.0) continue;
            float w = pow(1.0 + sqrt(r2) / 0.6, -2.2) * rim * rim;
            sum += w * source.sample(s, in.uv + float2(float(i), float(j)) * texel).rgb;
            ws += w;
        }
    }
    return float4(sum / ws, 1.0);
}

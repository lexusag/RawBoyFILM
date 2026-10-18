package app.rawboy.gl

/**
 * The develop pipeline, shared by the on-screen preview and the full-resolution export.
 *
 * Input: linear Rec.2020 scene light (1.0 = sensor clip), as-shot white balance.
 * Order: lens geometry (auto scale, manual + embedded distortion) -> denoise (spatial, linear)
 *        -> skin retouch (mask-guided, edge-aware) -> sharpening + local contrast + high-pass contrast (log-luminance ratios) -> bloom (scattered light)
 *        -> lens gains (vignetting) -> WB re-balance -> exposure -> shadows/highlights (log-luminance gain)
 *        -> purity-limited saturation -> tone mapper: AgX, filmic or sigmoid (log encode, grain, contrast, curve) -> whites/blacks
 *        -> soft gamut compression -> Rec.709 / sRGB encoding -> optional 3D LUT (display-referred, like the film LUTs expect)
 *        -> point curves (Pro's Curves: RGB, then per channel).
 * Film looks: scatter + halation before the lens gains, and the film, grain and print in place of the tone mapper,
 *        blended with the AgX render by Strength. Standard builds use shaders/film_form.glsl (RawBoy's own); the film
 *        edition (RAWBOY_FILM) shaders/film/film.glsl (spektrafilm's, GPLv3) instead.
 *
 * AgX constants follow Blender 4 / Filament / three.js (Rec.2020 inset/outset).
 */
internal object Shaders {

    const val VERTEX = ShaderSources.DEVELOP_VERT

    // The GLSL lives in <repo>/shaders (shared with the web build); ShaderSources is generated
    // from it at build time (engine/build.gradle.kts, generateShaderSources).
    private const val DEVELOP = ShaderSources.DEVELOP
    private const val MAIN_BODY = ShaderSources.MAIN_BODY
    private const val FILM = ShaderSources.FILM
    private const val FILM_FORM = ShaderSources.FILM_FORM

    fun fragment(forExport: Boolean): String = buildString {
        // highp int: fragment shaders default ints to mediump (16-bit on some GPUs),
        // which breaks the 32-bit grain hash.
        append("#version 300 es\nprecision highp float;\nprecision highp int;\nprecision highp sampler2D;\nprecision highp sampler3D;\n")
        // Film edition only (engine flavour "film"): spektrafilm, shaders/film. Standard builds have an empty FILM.
        if (FILM.isNotEmpty()) append("#define RAWBOY_FILM 1\n")
        append(if (forExport) "out uvec4 fragColor;\n" else "out vec4 fragColor;\n")
        append(DEVELOP)
        append(FILM.ifEmpty { FILM_FORM })
        append(MAIN_BODY)
        append("    if (uLutAmount > 0.0) c = clamp(applyLut(c), 0.0, 1.0);\n")
        append("    if (uCurveOn > 0.0) c = applyCurves(c);\n")
        if (forExport) {
            // 16-bit unsigned integer target: exact, core in GLES 3.0, no float-buffer extension needed.
            append("    fragColor = uvec4(uvec3(c * 65535.0 + 0.5), 65535u);\n")
        } else {
            append("    if (uClipWarn > 0.0) c = clipWarning(c);\n")
            append("    fragColor = vec4(c, 1.0);\n")
        }
        append("}\n")
    }
}

# Shaders

The develop pipeline's GLSL (ES 3.00), the single source of truth for every platform.

- `develop.vert`: vertex shader (fullscreen quad).
- `develop.glsl`: uniforms and every stage as functions (lens, denoise, retouch, sharpening, bloom, tone mappers AgX / filmic / sigmoid, LUT, point curves).
- `denoise.vert`, `denoise_split.frag`, `denoise_level.frag`, `denoise_shrink.frag`, `denoise_merge.frag`: the wavelet
  denoise pre-pass (complete shaders, run before the develop shader; see `engine/.../gl/WaveletDenoise.kt`). iOS uses
  hand ports in `ios/Engine/app/rawboy/metal/Helpers.metal`: keep them in step.
- `main_body.glsl`: the start of `main()`, in stage order. The platform code appends the output lines
  (LUT, curves, then a float or 16-bit integer target) and the closing brace; see `engine/.../gl/Shaders.kt`.

Android: the engine's `generateShaderSources` Gradle task turns each file into a Kotlin `const val`
at build time (`develop.glsl` → `DEVELOP`, `develop.vert` → `DEVELOP_VERT`, `denoise_split.frag` → `DENOISE_SPLIT_FRAG`). Keep `"""` out of the files,
and keep each one under 65 000 bytes (a JVM string-constant limit; split the file if needed).

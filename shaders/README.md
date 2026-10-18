# Shaders

The develop pipeline's GLSL (ES 3.00), the single source of truth for every platform.

- `develop.vert`: vertex shader (fullscreen quad).
- `develop.glsl`: uniforms and every stage as functions (lens, denoise, retouch, sharpening, bloom, tone mappers AgX / filmic / sigmoid, LUT, point curves).
- `main_body.glsl`: the start of `main()`, in stage order. The platform code appends the output lines
  (LUT, curves, then a float or 16-bit integer target) and the closing brace; see `engine/.../gl/Shaders.kt`.

Android: the engine's `generateShaderSources` Gradle task turns each file into a Kotlin `const val`
at build time (`develop.glsl` → `DEVELOP`, `develop.vert` → `DEVELOP_VERT`). Keep `"""` out of the files,
and keep each one under 65 000 bytes (a JVM string-constant limit; split the file if needed).

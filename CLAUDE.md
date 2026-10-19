# CLAUDE.md

**This is RawBoy Film's repository** (GPL-3.0-or-later, see LICENSE and README.md): the RawBoy engine and camera plus
the film-only files. RawBoy Pro, Auto and Web are not here; they, and the shared engine and camera, are developed in
RawBoy's own repository. Make engine and camera changes there and bring them over with `tools/sync_from_rawboy.sh`;
change only the film-only files, README.md, `ios/project.yml`, the release workflow and `settings.gradle.kts` here.
Sections below about Pro, Auto, the web build and `Readme.md` describe RawBoy's repository.

Guidance for working on RawBoy. Features, slider semantics and the changelog live in
`Readme.md`; this file covers how the code fits together, how to test, and the traps
already found.

## What it is

Android raw developer (Kotlin + Jetpack Compose, minSdk 29) with an AgX tone mapper (Pro also has filmic and sigmoid).
Native C++ decodes the raw into linear Rec.2020 half floats; a single GLES 3.0 fragment
shader does all development, both for the live preview and for tiled full-resolution export.
Three apps share one engine (plus **RawBoy Film**, a flavour of the camera, see below): **RawBoy** (`app-pro`, package `app.rawboy`, full controls),
**RawBoy Auto** (`app-auto`, `app.rawboy.auto`: automatic develop + film looks, batch export) and
**RawBoy Camera** (`app-camera`, `app.rawboy.camera`: live film-look viewfinder, raw capture, jpegli JPEG).
The owner tests on a Pixel 9 Pro.

## Layout

Gradle modules: `:engine` (Android library, namespace `app.rawboy.engine`; Kotlin packages stay
`app.rawboy.*`), `:app-pro`, `:app-auto`, `:app-camera`. Anything two apps need goes in the engine.

- `shaders/` — the develop GLSL (`develop.vert`, `develop.glsl`, `main_body.glsl`): **source of truth**,
  also meant for the future web build. `engine`'s `generateShaderSources` task turns them into
  `ShaderSources.kt` (build/generated); `gl/Shaders.kt` assembles the fragment shader from them.
- `engine/src/main/cpp/` — JNI library `librawboy.so`
  - `decoder_libraw.cpp` — main decoder (LibRaw 0.21.3, fetched by CMake). Outputs camera RGB
    unrotated, applies DNG gain maps per camera channel, converts to Rec.2020, then rotates.
  - `decoder_dng.cpp` — Adobe DNG SDK fallback (DNG 1.7 / JPEG XL). Only compiled when
    `third_party/dng_sdk` exists (see `third_party/README.md`); standard builds, release and debug, fail
    without it (`checkDngSdk`, on `preStandardReleaseBuild`): `-DCMAKE_BUILD_TYPE=Release` makes AGP run the Release
    CMake tasks for debug variants too (`mergeStandardDebugNativeLibs` → `buildCMakeRelease`), which hang off that
    task. The film edition never builds it (CMake `RAWBOY_USE_DNG_SDK=OFF`).
  - `lens_opcodes.cpp` — our own DNG OpcodeList2/3 parser (GainMap, WarpRectilinear, FixVignetteRadial).
  - `blemish.cpp` — blemish detection + spot healing on the full-res image (undoable via backups).
  - `jni_bridge.cpp` — decoder order (JPEG by magic, else LibRaw first, SDK fallback for TIFF-magic files), JNI API.
  - **Display sources** (JPEG/HEIC/PNG/TIFF, Auto's intake): `decoder_display.cpp` parses JPEG markers (EXIF via
    `exif_reader.cpp`, ICC via `display_profile.cpp`, MPF → Ultra HDR gain map, XMP `hdrgm` / ISO 21496-1; the gain is
    applied per channel only within 0.5 EV of scene white, so the default render stays the SDR picture) and decodes
    with jpegli through `rb_jpeg_decode` (`jpeg_decode.cpp`, built into the **JPEG** library: librawboy links librawboyjpeg,
    RawBoyNative links RawBoyJpeg, the web has one module). `display_inverse.cpp` takes display pixels back through
    the inverse of `develop()` at defaults, so no edits = the file; `DecodedImage::displayReferred` →
    `DevelopParams.displaySource` (set on the as-shot defaults only) → `uDisplaySource`: AgX **per channel** (no
    inset/outset) and no soft gamut compression, because with them saturated sRGB colours need negative light (yellow
    1,1,0 came back as 0.97,0.99,0.81). Keep its constants in step with `develop.glsl` (AgX range, sigmoid,
    `BASE_EXPOSURE_EV`). HEIC: platform decoders (Android ImageDecoder → RGBA_F16 linear extended sRGB bitmap via
    `openBitmap`/jnigraphics; iOS ImageIO → half-float context, orientation from ImageIO; Safari canvas, sRGB or P3
    bytes) → `decodeDisplayPixels`; EXIF from the HEIF Exif item (`findHeifExif`). PNG takes the same platform path
    (EXIF from `eXIf`, `findPngExif`); TIFF has our own reader (`decodeTiff`), tried by `decodeAny` only after LibRaw
    and the DNG SDK refuse the file (raws are TIFFs too). AutoTone gives display sources only the base grain; each
    Auto shows a "lower quality than RAW" note on importing them (`nonRawNote`, by extension). Test files:
    `.raw/display/` (DisplaySourceTests), `tools/display_roundtrip/check.mjs` (web; `--browser webkit` for HEIC:
    Playwright's WebKit decodes it through ImageIO like Safari. Its installer once hung after downloading; unzipping
    the zip from the temp folder into `~/Library/Caches/ms-playwright/webkit-<rev>` + an `INSTALLATION_COMPLETE` file
    worked. In it, a page *reload* blocked the decoder worker by COEP: set localStorage with `addInitScript`).
  - `jpeg_jpegli.cpp` + `jpegli.cmake` — separate library `librawboyjpeg.so`: jpegli (google/jpegli, fetched at
    pinned commits with its own Highway and libjpeg-turbo headers), 16-bit RGB in, 4:4:4. Separate .so with
    `--exclude-libs,ALL` because libjxl (DNG SDK path) bundles an older Highway. The DNG SDK's own
    `libjxl/lib/jpegli` is an unbuildable snapshot; don't try to use it.
- `engine/src/main/java/app/rawboy/`
  - `gl/Shaders.kt` — assembles the develop shader (GLSL ES 3.00) from `shaders/`; `shaders/main_body.glsl` shows the stage order.
    Edit the `.glsl` files, never the generated `ShaderSources.kt`.
  - `gl/ShaderParams.kt` — slider values → uniforms; WB and RGB-primaries matrices are combined here.
  - `gl/GlCommon.kt` — program/uniform binding, texture slots (units 0–6, see below), `SampleGeometry`.
  - `gl/DevelopRenderer.kt` (preview, fit + 100% view, exposure frame), `gl/OffscreenDeveloper.kt` (export tiles).
  - `lens/LensTransform.kt` — output→source geometry: user crop → rotation/straighten → flips (a mirror in `outToImage`, so masks, which sit on the
    image, follow with no shader change) → auto-scale → manual
    distortion → DNG warp. **Mirrors the shader math**; Kotlin uses it for tile bounds and auto crop.
    `frameWidth/Height` = rotated frame, `outputWidth/Height` = the crop; the crop is only a translation in
    `outToImage`, everything lens-related stays full-frame (`knownZoom` reuses the auto scale when only the crop changes).
  - `develop/CropMath.kt` — crop rect (fractions of the rotated + straightened frame), aspect ratios, handle drags,
    quarter-turn rotation. `develop/Presets.kt` — preset groups, capture/apply, the `.rawboy.json` format (org.json).
  - `session/PhotoSession.kt` — one open photo: decode, previews, skin mask, blemish scheduling,
    zoom tiles, LUT loading, export. Both apps' ViewModels delegate to it.
  - `gl/ThumbnailRenderer.kt` — develops a 512 px whole-image copy off screen (Auto's thumbnails),
    via `OffscreenDeveloper.renderPreview`.
  - `view/DevelopPreview.kt` (the GLSurfaceView composable), `view/SliderTapTracker.kt`, `view/CompactSlider.kt` (the
    one-row slider behind Pro's `ParamSlider`, Auto's and Camera's sliders), `view/LookDropdown.kt` (Pro's stock and Camera's look menu).
  - `view/Help.kt` — the top bars' "?" button and sheet. Each app's content is its `ui/Help.kt`, mirrored in iOS
    `ui/Help.swift` and web `ui/pro/help.ts` / `ui/auto/help.ts` (web: also Ctrl/⌘+/, `ui/common/HelpDialog.tsx`).
    Update them with user-visible controls and gestures.
  - `retouch/SkinSegmenter.kt` — MediaPipe skin segmentation + Face Landmarker feature exclusion.
- `app-pro/src/main/java/app/rawboy/ui/` — `EditorViewModel.kt` (Pro state, carry-over, presets), `Controls.kt`
  (tabs Light, Color, Detail, LUTs, Lens, `ParamSlider`, Crop & rotate panel), `EditorScreen.kt`,
  `CropOverlay.kt` (crop frame + gestures; the preview shows `params.withoutCrop()` while the tool is open),
  `PresetsPanel.kt` (top-bar mode like Crop & rotate, replaces the tabs while open), `PresetStore.kt`
  (`filesDir/presets.json`). Web mirrors these in `src/ui/pro/` (presets in localStorage).
- `engine/src/main/java/app/rawboy/auto/` (shared by Auto and Camera) — `AutoAnalysis` (decode → 512 px copies →
  skin → stats → auto develop; `Analysis`), `analysis/` (`SceneAnalyzer`: stats from the 512 px copy;
  `AutoTone`: exposure/tone/noise as ordinary `DevelopParams`), `looks/` (`Looks` catalogue on the bundled
  LUTs, `LookApplier` = auto base + look × Strength + the user's `Adjustments`, `LookSuggester`).
- `app-auto/src/main/java/app/rawboy/auto/` — `AutoViewModel.kt` (batch queue, export all), `ui/` (grid, detail).
- Viewfinder (engine): `gl/ViewfinderRenderer.kt` — camera SurfaceTexture (OES) → pass 1 inverse sRGB/gamma,
  sRGB→Rec.2020, × scale, rotated upright into an RGBA16F texture (row 0 = top) → pass 2 the normal
  `DevelopProgram`. Base (512), bloom halo (512, boosted) and tail (128) are rendered on the GPU **every frame**
  (`makeCopies`, bound via `DevelopProgram.bindLiveCopies`): built from the readback they lagged and bright lights
  left trails. Every ~300 ms the 512 px base is read back → `gl/ViewfinderBase.kt` (analysis only).
- `app-camera/src/main/java/app/rawboy/camera/` — `capture/CameraController.kt` (Camera2: back camera with RAW,
  preview with `TONEMAP_PRESET_CURVE_SRGB`, `DngCreator` with lens shading map on, tap-to-focus, as-shot white from
  `SENSOR_NEUTRAL_COLOR_POINT` + D65 ColorMatrix; `lenses()`: the logical back camera = W, its physical cameras with
  RAW = UW/T by 35 mm-equivalent focal; a physical lens is streamed via `OutputConfiguration.setPhysicalCameraId`,
  with per-lens keys (`setPhysicalCameraKey`) and per-lens results/characteristics for DngCreator; fallback: open the
  physical id directly), `CameraViewModel.kt` (live auto develop, capture → DNG →
  one-at-a-time develop → jpegli JPEG, `previewScale` calibration from raw key / viewfinder key),
  `CameraSettings.kt`, `ui/`.
- `luts/` — bundled LUTs, copied into the engine's assets by the `copyLuts` Gradle task.
  User-visible look/stock names and bundled LUT file names are RawBoy's own (Yellow/Green families, 2026-10-18): no
  film makers' brand or product names (`tools/spektrafilm_luts/generate.py` writes them into `stocks.json`); old LUT
  file ids map to the new ones in `RetiredLooks`.
- `licenses/THIRD_PARTY_NOTICES.txt` — every third-party licence plus the DNG patent notice (RawBoy Film:
  `THIRD_PARTY_NOTICES_FILM.txt`, its GPLv3 notice, the spektrafilm licences and the GPLv3 text, no Adobe sections;
  `copyNoticesFilm`); packaged by the engine's
  `copyNotices` task (shown by `view/Licenses.kt`: at the end of Pro's/Auto's "?" help sheet, Camera's Settings) and copied
  into the web build (`ui/common/LicensesDialog.tsx`). Update it when adding or upgrading a dependency.
- **Editions** (flavour dimension `edition` on `:engine` and `:app-camera`; Pro/Auto use
  `missingDimensionStrategy("edition", "standard")`): `standard` = every app as before; `film` = **RawBoy Film**
  (`app.rawboy.camera.film`, GPLv3). The film edition adds `shaders/film/` (generateShaderSourcesFilm; standard gets
  an empty `ShaderSources.FILM`, and `Shaders.kt` defines `RAWBOY_FILM` and assembles `FILM` in place of `FILM_FORM`
  only when it isn't empty), its notices and `BuildConfig.FILM_EDITION` (`Licenses.kt` shows `GPL_NOTICE` instead of
  `DNG_NOTICE`). Both editions bundle `luts/spektrafilm` (`copyLuts`). The camera's flavour source sets hold
  `Edition.kt` (looks, default look, Pictures folder, Settings
  credits). Verified in the APKs: standard ones have no GPL film shader (check: no `GPL-3.0-or-later` in the dex) and
  the DNG SDK; RawBoy Film the reverse. Tasks: `:app-camera:assembleStandardDebug` / `assembleFilmDebug`, engine tests
  `:engine:testStandardDebugUnitTest`.
- **Auto's Film mode** (standard apps, Android/iOS/web, GPL-free): `shaders/film_form.glsl` is RawBoy's own film
  formation with the same entry points as `film/film.glsl` (`filmOptics`, `applyFilm`), assembled in its place in
  every standard build; `develop.glsl`/`main_body.glsl` call them unconditionally behind `uFilmAmount > 0`. Formation:
  contrast as a log-exposure slope around 0.18 → Apple Log (Apple's published curve) → film LUT → density wire →
  grain → print LUT. Grain: Selwyn's law from an RMS granularity at 48 um, density dependence D(1 − D/Dsat), per-pixel
  Gaussian with half-weight neighbours, footprint-scaled. Halation: a Gaussian of half the base-reflection disc radius
  from the **highlight-boosted bloom copy** (`uBloomHalo`; `uBase` without one; light sources sit far above clip and
  only they halate visibly), mixed in per channel. Scatter: 4 diagonal taps. Parameters: `luts/spektrafilm/
  formation.json` (`FilmStock.formation`; uniforms `uForm*`), fitted to RawBoy Film's renders with
  `tools/film_eval/compare.mjs` (renders both shaders through the web dev server; reports formation dE2000, grain
  std per tone band, halation's added light). **Clean-room rule:** never read or copy `film/film.glsl` or spektrafilm's
  code into `film_form.glsl`; tune on output statistics only. Results (2026-10-13, IMG_0805/IMG_4239): formation dE
  median 0, grain within ~10% (slides' R/B within ~20%), halation red group (Gold, X-Tra,
  Kodachrome) vs neutral group. Auto keeps one pick per mode (`AutoPhoto.mode`, `filmLook`, `suggestedFilm`;
  `LookSuggester.suggestFilm`); removed looks/stocks/LUTs map to kept ones via `RetiredLooks` (all ports: `byId`,
  preset apply, LUT open). Film-look thumbnails are keyed `film:<id>` (stock ids equal LUT look ids). Grain
  ±50 scales `filmGrain` 0..200. `PhotoSession.selectFilm`/`filmData` (filmLoader) like `selectLut`. Web: cubes are
  URL assets fetched per stock (`film/FilmStocks.ts`). **Pro's Film tone mapper** (all platforms): the 4th chip/segment
  is selected iff `params.film != null`; `toneMapper` keeps its value (what Film strength < 100 blends with).
  While `film` is set, `ShaderParams.from` drops the user LUT (all ports; Pro hides its LUTs tab, `params.lut` is kept). `setToneMapper` clears `film`, `useFilm`/`selectFilm` set it (last stock remembered per session, `filmAmount` 100 on
  turning on) and load it through `PhotoSession.selectFilm` (Pro VMs pass a `filmLoader`; also on open, preset apply,
  Reset). Android's preview passes `film = filmData` to `ShaderParams.from` (EditorScreen). Film fields are in the
  Light preset group (`Presets.kt/.ts/.swift`; tests `PresetsTest.kt`, `engine.test.ts`). Vite doesn't
  watch `<repo>/shaders`: restart the dev server after editing them (and before `film_eval`, whose page patch
  otherwise reaches stale `?t=` module copies). Not tuned yet: Auto's exposure targets were set for AgX; the film
  print renders darker shadows (owner to judge).
- spektrafilm film looks (RawBoy Film; its LUTs are also Auto's Film mode):
  `tools/spektrafilm_luts/generate.py` (uv; spektrafilm pinned at 500bc42) bakes each stock with spektrafilm's own
  LUT creator, 2-LUT topology, Apple Log in → normalised film CMY density → sRGB, into `luts/spektrafilm/` with
  `stocks.json` (density wire, grain and halation parameters after `digest_params`: per-stock halation differs).
  Slides use `io.scan_film` (a `BundleBuilder` subclass). The bundle's input gain is 1, i.e. linear 0.18 = middle
  grey, which is RawBoy's grey after `uExposure`, so no exposure anchor is needed. `shaders/film/film.glsl` (only with
  `#define RAWBOY_FILM`: film edition only; everything else assembles `film_form.glsl` instead) ports halation + scatter
  (`filmOptics`, before lens gains, halation from the 512 px base copy) and grain (`filmGrain`, Gaussian with
  spektrafilm's Poisson-binomial mean/variance per output pixel + its 0.65 px blur). `develop()` branches after
  saturation: `applyFilm` → `applyLevels`, mixed with the AgX render by `uFilmAmount` (Strength). LUTs on units
  8/9 (`FilmTextures`), stock data `film/FilmStocks.kt`, looks `auto/looks/FilmLooks.kt`, `DevelopParams.film*`.
  Paper white is ~0.85 sRGB (spektrafilm's print), not 1.0. spektrafilm's own LUT QA (`spektrafilm-lut build … --qa`)
  on Portra 400: off-grid trilinear dE2000 median 0.11, p99 1.6, max 16 at saturated gamut edges.
  Performance: Pixel 9 Pro viewfinder ~35 fps, GL thread 2.0 ms/frame. The emulator (SwiftShader, CPU) stalls on the
  halation loop (49 base taps x 3 exp per pixel): test RawBoy Film's viewfinder on the phone, or precompute the halo
  at 512 px per frame (bloom-style) if it ever matters on a device.
- `tools/film_luts/generate.py` — regenerates the film `.cube` LUTs with spectral_film_lut (`uv run …`). It contains
  the inverse of AgX from `shaders/develop.glsl`: a change to AgX's constants or curve needs the same change there and
  a regeneration. Colour negatives are scan-inverted (an RA-4 print was too contrasty on top of AgX); Aerochrome was
  tried for infrared and doesn't work from RGB (no IR signal). Per-stock contrast (scan gamma / push) and saturation in `STOCKS` were
  fitted (one-off script, not in the repo) to the old HaldCLUT looks' Oklab L std and mean chroma in Auto (Strength
  80 + their old deltas), never below the datasheet-neutral values; the owner found the neutral versions bland.
- `engine/src/main/assets/models/` — MediaPipe models (stored uncompressed: `noCompress` tflite/task).
- `web/` — RawBoy Web (Vite + TypeScript + React; see `web/README.md`). `web/native/` builds the engine's C++ to
  WASM with Emscripten, using `engine/src/main/cpp` in place (`wasm_bridge.cpp` = embind counterpart of
  `jni_bridge.cpp`/`band_ops.cpp`/`jpeg_jpegli.cpp`); `rawboy-full` adds the DNG SDK when `third_party/dng_sdk` exists (loaded first; `build:public` requires it).
  `web/src/engine/` is a file-for-file TS port of the Kotlin engine (same names; keep both in step: a change to
  ShaderParams, LensTransform, WhiteBalance, AutoTone, Looks… needs the same change in the `.ts`). `src/worker/` hosts
  the WASM; `src/ui/pro` and `src/ui/auto` port the two apps' view models (`ProStore`, `AutoStore`) and screens.
  `src/ui/browser` is web-only (no Kotlin/Swift counterpart): RawBoy Browser, culling a folder. Previews come from
  `engine/browse/EmbeddedPreview.ts` (the cameras' embedded JPEGs found by walking TIFF IFDs/SubIFDs, CR3 boxes and
  the RAF header with `Blob.slice`, never the raw decoder; a scan for JPEG streams is the fallback, and lossless JPEG
  = sensor data is rejected by its SOF). Ratings: `engine/browse/XmpSidecar.ts` edits only `xmp:Rating` and
  `rawboy:Pick` in place, so other apps' sidecars survive. Bin = `_bin` subfolder via `FileSystemHandle.move` (copy +
  size check + remove where move is missing). Browser-tested with OPFS standing in for `showDirectoryPicker` and
  generated TIFF/CR3/RAF files (no real raws needed).
  Shared native pieces: `decode.cpp` (decoder order), `image_ops.cpp` (downsample, copyTile), `band_ops.h`,
  `jpegli_settings.h`, `dng_sdk.cmake` (platform define is a parameter: `qAndroid=1` / `qWeb=1`).
- `.raw/` (git-ignored) — test files: `retouch.RAF` (GFX 50R portrait, orientation 8 after decode), `DSCF3079.RAF`
  (same body, ISO 6400, ~7 stops under: hot/warm pixels),
  `P4270088-compressed.dng` (Olympus, DNG 1.7 JPEG XL, Linear Raw), `IMG_4239/4259/4260.DNG` (iPhone 17 Pro Max
  ProRAW: Linear Raw, 10-bit lossless JPEG tiles, BaselineExposure up to +4.9 EV, WB in AnalogBalance).

## Build

- `./gradlew :app-pro:assembleDebug` / `:app-auto:assembleDebug` / `:app-camera:assembleStandardDebug` /
  `:app-camera:assembleFilmDebug` (`install…` likewise). Gradle daemon is pinned to JDK 21
  (`gradle/gradle-daemon-jvm.properties`): Gradle 8.11 can't run on Android Studio's bundled Java 25.
- NDK 27.2, CMake 3.22.1 (avoid CMake ≥3.24-only options such as `DOWNLOAD_EXTRACT_TIMESTAMP`).
- ABIs come from `rawboy.abis` (`gradle.properties`, read by the engine and all apps): arm64-v8a + x86_64 locally;
  the release workflow passes `-Prawboy.abis=arm64-v8a`. It runs on main (a release only dispatches it) so its
  ccache/Gradle/Emscripten caches carry over between releases.
- Native libs must stay 16 KB page-aligned (`-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON`). Check with
  `llvm-readelf -lW librawboy.so` (LOAD align 0x4000).
- Release: signing from `keystore.properties` (git-ignored). R8 + resource shrinking; keep rules for
  MediaPipe, protobuf and Flogger are required (`engine/consumer-rules.pro`, applied to both apps). **Test release builds on the
  emulator** (sign a copy with the debug key) — R8 has broken things that debug builds didn't show.
- Incremental builds that finish in 1–4 s may not have recompiled; when in doubt use `--rerun-tasks`
  or check the APK (sizes, `strings` on the .so).

## Testing approach (what has worked)

- **Emulator:** `Medium_Phone_API_37.0` (arm64). It renders GLES in software, so frames are slow:
  wait 5–15 s after an input before screenshots, and expect `uiautomator dump` readouts to lag a step.
- **adb input:** `input tap` sends a zero-length press that Compose sliders sometimes ignore. Use
  `input swipe x y x y 80` for taps; double tap = two 60 ms swipes 0.2 s apart. Don't start swipes at
  the screen's left edge (back gesture).
- **Opening files:** push to `/sdcard/Pictures`, broadcast `MEDIA_SCANNER_SCAN_FILE`, look up the `_id`
  in `content://media/external/images/media`, then
  `am start -W -a VIEW -d content://media/external/images/media/<id> -t <mime> --grant-read-uri-permission -n app.rawboy/.MainActivity`.
  After reinstalling, `am force-stop` first or the intent races the restart. The emulator may restore an
  old snapshot, so uninstall + reinstall before testing a new build.
- **Measure, don't eyeball:** crop screenshots with Python/PIL and compare means, channel spreads,
  diffs between states. Exports: check tile-edge steps against nearby JPEG 8×8 block edges for seams.
- **Native code on the Mac:** compile LibRaw + the decoders with host clang and a small `main()` to
  test decoding/healing quickly (a scratch harness was used; it isn't in the repo).
- **Shaders:** validate with the NDK's `glslc` by swapping `#version 300 es` → `310 es`. Note glslc
  uses 32-bit ints regardless, so it can't catch mediump problems.
- No device is connected by default; the owner tests on the phone.
- **Camera on the emulator:** the emulated back camera has RAW but no `CONTROL_POST_RAW_SENSITIVITY_BOOST`, so
  `DngCreator` throws ("Missing metadata fields for tag BaselineExposure"). The front camera (logical, physical
  lenses 4 and 5) has it: for an end-to-end emulator test, including lens switching, temporarily select
  `LENS_FACING_FRONT` in `CameraController.lenses()`. The emulator is
  slow (first live analysis ~5 s, a shot ~15 s); on the phone it's much faster.
- **jpegli on the Mac:** compile the fetched sources (`engine/.cxx/.../_deps/jpegli-src`, `jpegli_hwy-src`,
  `jpegli_include`) with host clang and a small `main()`; check 4:4:4 with PIL `JpegImagePlugin.get_sampling` (0).

## Web build (what has worked)

- `cd web && npm run wasm && npm run dev`. Always run Vite through the npm scripts or `web/node_modules/.bin/vite`:
  a bare `npx vite` from another cwd fetched a different Vite and served the parent folder.
- Browser tests: `playwright-core` driving the installed Chrome headless (`channel: 'chrome'`), kept outside the repo;
  open files through the file chooser, then measure screenshots/exports with PIL as on Android. Exports were checked
  for size, 4:4:4 (`get_sampling` 0), preview-vs-export means (±0.2 levels) and tile seams (16-bit TIFF, edge rows vs
  neighbours).
- Traps: Vite indexes `public/` when the dev server starts, so generated assets (models, MediaPipe WASM, coi script)
  are copied in the plugin's `config()` hook, not `buildStart()`. MediaPipe's WASM and models must be same-origin
  (COEP). Homebrew's CMake 4 refuses libjxl's bundled deps without `-DCMAKE_POLICY_VERSION_MINIMUM=3.5`. The pthread
  pool is fixed at 8 (`parallelRows`' maximum) with `PTHREAD_POOL_SIZE_STRICT=2`: a worker blocked in C++ can't grow
  the pool, so more threads would deadlock. `getExceptionMessage` needs `-sEXPORT_EXCEPTION_HANDLING_HELPERS`. The
  DNG SDK needs `qWeb=1 qDNGLittleEndian=1` (its endian detection doesn't know wasm32). LibRaw is built without
  OpenMP for the web (no `_OPENMP` anywhere, so its headers agree). LibRaw needs `LIBRAW_OWN_SWAB` on the web: it
  byte-swaps big-endian data in place (`swab(p, p, n)`), which musl's `swab` corrupts (iPhone ProRAW is `MM`: garbage
  LinearizationTable, white posterised image). macOS libc and bionic happen to cope. When web and native decodes
  differ, compile a harness with `em++` (same flags, `-sNODERAWFS=1`) and run it in Node next to the clang one.
- Web EXIF: `DecodedImage::metadata` (filled by both decoders, unused on Android) → `ExifWriter.ts`. LibRaw quirks:
  for Canon `other.artist` is the makernote owner name (left out); ApertureValue 0 becomes f/1.0 (dropped when the
  focal length is unknown too); no exposure compensation, OffsetTime or original make/model strings. Checked with
  `exiftool` source vs export on the `.raw/` files.
- Web on Chrome Android (Pixel 9 Pro, Mali-G715 via ANGLE): `IMPLEMENTATION_COLOR_READ_TYPE` says UNSIGNED_SHORT for
  the RGBA16UI target, but that `readPixels` fails with INVALID_OPERATION ("not handled by RGBA16 readPixels
  workaround"), and on Chrome 155 that error **loses the WebGL context** (photo blinks black, then "WebGL2 is not
  available"): `OffscreenDeveloper` reads back UNSIGNED_INT only, never tries UNSIGNED_SHORT. Debug the phone's Chrome over adb:
  `adb forward tcp:9333 localabstract:chrome_devtools_remote`, then playwright-core `connectOverCDP`.

## iOS build (in progress; see ios/)

- Native Swift port: `ios/project.yml` (XcodeGen; `RawBoy.xcodeproj` is generated, git-ignored). Targets `RawBoyEngine`
  (framework), `RawBoyAuto`, `RawBoyPro`, `RawBoyCamera` (apps), `RawBoyEngineTests`. `ios/Engine/app/rawboy/` is a file-for-file
  Swift port of the Kotlin engine (same names, `gl/` → `metal/`): a 4th copy to keep in step with Kotlin and TS.
- Steps: `ios/native/build.sh` (C++ → `RawBoyNative.xcframework` + `RawBoyJpeg.xcframework`, engine sources in place,
  C APIs `rawboy_native.h` / `rawboy_jpeg.h`), then `cd ios && xcodegen`, then
  `xcodebuild test -scheme RawBoyEngineTests -destination 'platform=iOS Simulator,name=iPhone 18 Pro Max'`.
  `xcode-select` points at the Command Line Tools here: export `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`.
- Shaders: `ios/shaders/gen.sh` assembles the fragment shader as `Shaders.kt` does, glslang `-V -R` (relaxed rules pack the
  loose uniforms into one block) → spirv-cross `--msl --msl-decoration-binding` → `ios/build/generated/*.metal` plus
  `DevelopLayout.swift` (std140 offsets from the reflection; `UniformBlock` writes uniforms by name). Runs before every
  engine build. The reflection's `block_size` is the std140 size, but SPIRV-Cross pads the MSL struct to 16 bytes:
  gen.sh rounds up, or Metal API validation (on when run from Xcode, off in xctest) aborts every draw with
  "has space for 1384 bytes, but argument has a length(1392)". Test with `SIMCTL_CHILD_MTL_DEBUG_LAYER=1`.
  Metal render targets have row 0 at the top: export draws with `flipY` on (Android: off); on-screen both on.
- **RawBoy Film on iOS** (as Android's editions): `RawBoyFilmEngine` framework = the same `Engine` sources with
  `FILM_EDITION`, `build/generated-film` (gen.sh's second pass: `#define RAWBOY_FILM 1` + `shaders/film/film.glsl`),
  `../luts` incl. spektrafilm, `THIRD_PARTY_NOTICES_FILM.txt`, and `RawBoyNativeFilm.xcframework` (native/build.sh's
  film pass: `-DRAWBOY_USE_DNG_SDK=OFF -DRAWBOY_NATIVE_NAME=RawBoyNativeFilm`, LibRaw only; a distinct framework name
  so both natives can sit in one build). `RawBoyFilm` app (`app.rawboy.camera.film`) = `Camera` minus `standard/` plus
  `film/Edition.swift`; `RawBoyCamera` excludes `film/`. Engine/Camera files import the edition's module under
  `#if FILM_EDITION`. Film textures are reflected by name (`uFilmLut`/`uPrintLut` in `DevelopTextures`), uniforms by
  `bindFilm` (`UniformBlock.vec3`). Both engines bundle `luts/spektrafilm` (Auto's Film mode). Tests:
  `RawBoyFilmEngineTests` (`FilmEngineTests/`); the standard engine's film formation: `EngineTests/FilmFormationTests`.
  Build for a named simulator (`platform=iOS Simulator,name=…`): `generic/platform=iOS Simulator` also links x86_64,
  which the arm64-only native frameworks can't. A free Personal Team
  allows 3 sideloaded apps per device: remove Pro or Auto to have Camera and Film side by side.
- Two frameworks, as Android's two .so: jpegli and libjxl (DNG SDK) bundle different Highway versions. Each exports only
  `_rb_*` (`-exported_symbol`). LibRaw without OpenMP (Apple clang), so decodes are slower than on Android.
- Verified on the Simulator: decodes of every `.raw/` file identical to `web/scripts/decode-check.mjs`; a defaults export
  of IMG_0805.CR2 matches the web export (mean diff 0, 99.99% of pixels within 0.004 levels); no tile seams; EXIF via
  `ExifWriter.swift` (port of `ExifWriter.ts`: ImageIO's lossless metadata copy moved tags to XMP and truncated rationals).
- Simulator testing: the Simulator reads the Mac's files, so tests use `.raw/` directly and write to `ios/build/test-output`.
  Debug apps import `RAWBOY_IMPORT` paths at launch: `SIMCTL_CHILD_RAWBOY_IMPORT=a.CR2:b.DNG xcrun simctl launch …`.
  Imports are copied into Application Support/Imports (iOS lends picked files only briefly). Exports go to Photos albums.
- UI on the phone: `Color.accentColor` follows the asset catalogue's AccentColor (set per app), not `.tint`. Her phone has
  Accessibility › Button Shapes on (capsules behind and underlines under plain buttons; the Simulator doesn't): Pro's
  top bar uses its own `BarButtonStyle`, or the capsules push the row off both edges. Screenshot the phone with
  `xcrun devicectl device capture screenshot --device … --destination x.png`.
- Pro (`ios/Pro`): mask touches are a UIKit view (`MaskTouchView`) that claims only handle hits in Move mode, so other
  touches fall through to the SwiftUI preview gestures. User LUTs: Documents/LUTs (Files app).
- Skin: MediaPipe Tasks for iOS 0.10.33 without CocoaPods: `ios/mediapipe/fetch.sh` downloads the pods' tarballs and
  rebuilds their xcframeworks (the shipped Info.plist claims a `.a` + Headers but holds `.framework`s). Static, linked
  into RawBoyEngine with `-ObjC` (else "-[NSString cppString]: unrecognized selector") and `-force_load` of the
  Common graph library, as the podspec does. Same models as Android (`engine/src/main/assets/models`).
- Camera (`ios/Camera`, tested on an iPhone 17 Pro Max, iOS 26): AVFoundation, one physical lens at a time (Bayer RAW
  isn't offered on the virtual multi-camera devices). Default raw is **ProRAW at 12 MP** (`RawFormat.proRaw`): far less
  grain than a single Bayer frame in low light (ISO 2500-8000 on UW/T); Bayer stays a setting. Shots save in ~3-4 s.
- Camera viewfinder: frames are BGRA sRGB (`activeColorSpace = .sRGB`, video HDR off); the connection stays at angle 0,
  unmirrored (AVFoundation ignored the selfie connection's rotation after a switch). `linearizeFragment` rotates and
  mirrors, adapting to the buffer: back lenses deliver landscape buffers (one clockwise quarter turn), the front camera
  delivers **portrait** ones (no turn, mirror only). Held sideways, photos come out landscape and upright (back and front checked); the
  debug viewfinder PNG stays in screen orientation, so it looks turned. Then the sRGB curve + primaries × `previewScale` (per lens key, "P"
  prefix for ProRAW, "F" for front; learned per shot, flash shots skipped). In dim light ProRAW's raw is ~8x brighter
  than the video frame (scales 3-6 vs 0.35 default), so the viewfinder only matches after a lens's first shot.
  The video frames are rendered by Apple (more saturated, highlights squeezed into the last ~1.5 stops): pass 1
  undoes it towards the raw with a measured luminance curve (`kCameraCurve` in Helpers.metal, `Frame.toneCurve`) and
  chroma power 0.58 (`Frame.saturation`), fitted on the debug pairs (viewfinder copy at the shutter vs
  `DeviceDngTests`' `.f32` raw copy). A plain 0.8 contrast slope fitted the mid-tones but left whites 0.7 stops grey.
  Backlight: iOS lifts the video's dark areas locally (can't be undone globally), and the owner preferred that, so
  both Camera ports raise shots' shadows with the scene range (`backlightShadows`: (p98 - p5 - 5) × 37.5, max 80,
  fading out ISO 800-3200; 75 matched the viewfinder on a room-against-window shot). Shots only, not the viewfinder.
  **Open: iPhone photos don't match the Pixel's looks** (owner: "need to find a way to match looks"). Tried and
  rejected: flat +15 contrast/saturation (iOS only). Tried, not adopted (2026-10-13): ProRAW's ProfileGainTableMap
  (DNG 1.6; Apple's: 12x16 grid x 257 gains, weights ~0.07R+0.25G+0.03B+0.35max over linear ProPhoto after
  BaselineExposure; maths in the DNG SDK's RefBaselineProfileGainTableMap). It is mostly one global tone compression
  (gain ~12x deep shadows, ~2x at grey, 0.7x top; output tops out at 0.7) with only +-20% local variation. Applied in
  the decoder before AgX it flattened everything and pinned auto exposure at -2 (double tone mapping); its local
  part alone (each cell over the median cell) only added slight local contrast. The DNGs also carry a
  ProfileToneCurve and SemanticName mattes (unused). Next: the same 3-4 scenes (backlit, indoor, a face) on the Pixel
  and the iPhone, same look, then measure where the developed outputs differ (contrast, colour, saturation,
  shadows) and correct that on iOS. Phone DNGs: `ios/build/device-debug-new`; renders `ios/build/test-output/pgtm-compare-*.jpg`.
  Grain: ProRAW ×3 (no sensor noise left after the merge; the look's grain alone was < 1 level std).
  `startRunning` only after `commitConfiguration` (else an exception). As-shot white from `deviceWhiteBalanceGains` →
  `chromaticityValues`. None of the Pixel workarounds (YUV matrix, tone curve, EXTRA_BLACK, manual flash) is ported;
  the camera's own flash exposure works on the iPhone.
- Camera hardware shutter: volume up/down and the Camera Control click take a photo (`HardwareShutter`,
  `onCameraCaptureEvent`, iOS 18+), as Android's volume keys. Launching RawBoy Camera from Camera Control would need a
  LockedCameraCapture extension (not done).
- iPhone DNG traps (shared C++, so Android/web too): OpcodeList3 GainMaps for a 3-plane (demosaiced/ProRAW or
  rearranged) image apply per plane (`GainMap::list`, `MapPlane`), not to red only (tele had red edges). The selfie
  camera's portrait **CFA** DNG is mis-assembled by LibRaw (shifted bands, red cast): `decoder_libraw.cpp` hands
  portrait Apple CFA DNGs to the DNG SDK. `DeviceDngTests` decodes phone DNGs from `ios/build/device-debug`.
- On the phone: `native/build.sh` re-signs each framework binary with identifier `app.rawboy.native.<name>` (the linker's
  ad-hoc "<name>-<hash>" fails install: MismatchedBundleIDSigningIdentifier). Logs: `xcrun devicectl device process
  launch --console --environment-variables '{"OS_ACTIVITY_DT_MODE": "enable"}' app.rawboy.camera` (log at `.notice`;
  debug lines don't show). Debug builds keep each shot's JPEG, viewfinder PNG and DNG in Documents/debug
  (`DebugSnapshots`): `xcrun devicectl device copy from --domain-type appDataContainer --domain-identifier
  app.rawboy.camera --source Documents/debug --destination …`. Icons: `python3 ios/icons/gen.py` from the Android
  vectors. Signing: owner's free Personal Team (apps expire after 7 days: rebuild and reinstall).

## Coordinate spaces and pipeline

- Decoded image: full-res, **display orientation**, linear Rec.2020 D65, as-shot WB, 1.0 = sensor clip.
  `sensorWidth/Height` + TIFF `orientation` map back to sensor space for DNG opcodes.
- Shader per output pixel: `outPx` (cropped, rotated/straightened output) → `uOutToImage` → lens pre (auto-scale,
  manual distortion) → sensor space → DNG warp (per plane for lateral CA) → back to image space → sample.
- Spatial effects use neighbourhoods, so export tiles and the 100% view fetch a source region from
  `LensTransform.sourceBounds(out, ShaderParams.spatialMargin)`. Anything that widens a filter must
  widen `spatialMargin`.
- Large-radius effects (high-pass contrast, bloom, skin mask) sample small **whole-image textures**
  (512/128 px) so preview, 100% and export match without seams.
- Texture units: 0 image, 1 LUT (3D), 2 lens shading, 3 base (512), 4 bloom halo, 5 bloom tail, 6 skin mask, 7 brush atlas,
  8 film LUT, 9 print LUT (film looks: RawBoy Film and Auto's Film mode, all platforms).
- Mask layers (`develop/Masks.ts`, web only so far): `DevelopParams.masks`, positions in fractions of the decoded image,
  evaluated in the shader at `srcG` (the sampled image pixel), so they stay on the content through crop/lens. Analytic
  gradients only (no texture, no neighbourhood, so no `spatialMargin`); packed by `ShaderParams.packLayers` into
  `MAX_LAYERS` (4) uniform arrays. Layer adjustments blend the *parameters* (EV, contrast slope, shadows/highlights,
  saturation, clarity amount, a WB matrix) by the mask weight in `develop()`. Every mask uniform is "off" at 0
  (`uMaskView` = layer + 1), so Android, which doesn't bind them yet, renders as before. `LensTransform.output` (Newton
  inverse of `source`) draws the handles. Masks don't carry over to the next photo; presets have a `masks` group.
- Masks on Android mirror the web files: `develop/Masks.kt`, `develop/BrushRaster.kt` (sync on the UI thread,
  `@Synchronized` upload from the GL/export threads), `ShaderParams.packLayers`, `BrushTexture` (unit 7) in
  `DevelopRenderer` and `OffscreenDeveloper` (the viewfinder needs none), `LensTransform.output`, `ZoomMath.zoomedRect`.
  UI: `app-pro/.../ui/MasksPanel.kt`, `MaskOverlay.kt` (drawing + `rememberMaskGestures`, applied **last** on the
  preview's gesture layer: Compose siblings don't share touches, so the mask gestures live on that layer and consume
  only handles/strokes). `EditorTopBar.kt` holds the top bar and `EditorMode`.
- Engine JVM tests: `./gradlew :engine:testStandardDebugUnitTest` (`engine/src/test`, junit + org.json; `retouch/Filters.kt`
  keeps `box` out of `SkinSegmenter`, which loads MediaPipe).
- Brush masks (`develop/BrushRaster.ts`): strokes are vectors in `MaskLayer.strokes` (image fractions), rasterised on
  the CPU at the session preview's size into paint/erase channels (stroke order matters; `sync` only adds new dabs
  while a stroke grows, else redraws). `PhotoSession.brushRaster` caches one per layer. GPU: texture unit 7, one RG8
  **2x2 atlas** (`BrushTexture`, partial uploads from `pendingRect`), not a `sampler2DArray`: an unbound sampler sits on
  unit 0 with `uImage`, and two sampler types on one unit is a draw error (Android doesn't bind it yet). Auto mask gates
  each dab by luminance/chroma likeness to the centre, from a blurred guide (`makeGuide`; noise floor tuned on the
  ISO-high night shot `IMG_4914.CR2`). Presets drop strokes (`forPreset`).
- Grain and denoise are sized in full-res pixels; fit-view strength is scaled by footprint.
- Tone mappers (`DevelopParams.toneMapper` → `uToneMapper`: 0 AgX, 1 filmic, 2 sigmoid). Filmic and sigmoid reuse AgX's
  log range, grey (display 0.4967 at `AGX_PIVOT`) and slope there (2.057), so slider meanings and `BASE_EXPOSURE_EV`
  hold for all three; they skip AgX's inset/outset and output the same 2.2 display encoding. Their constants were
  fitted to `agxSigmoid` (a change to AgX's curve or range needs a refit). Only Pro sets it: Auto/Camera stay AgX
  because the film LUTs are built on the AgX inverse (`generate.py`) and AutoTone uses AgX's white point.
- Curves (`DevelopParams.curves`, `develop/ToneCurve.kt/.ts/.swift`): point curves on the final sRGB value, applied by
  each platform's shader assembly **after** the LUT line (`Shaders.kt`, `Shaders.ts`, `ios/shaders/gen.sh` x2). The CPU
  composes RGB then each channel into `ToneCurves.table()` (33 rgb triples, `uCurve`, linear interpolation in
  `applyCurves`; identity = null = `uCurveOn` 0). UI: `CurvesPanel` in each Pro's Light tab; a touch grabs only near a
  point or the curve (web 28 px, apps 24 dp/pt; a new point lands on the curve), elsewhere the panel scrolls. Web:
  `touch-action: pan-y` + non-passive touchstart/touchmove cancelled while dragging. Android: the down stays unconsumed.
  iOS: `CurveTouchLayer` (UIKit) hit-tests only near the curve and claims with a zero-duration long press. Presets: own `curves` group, `[[x, y], ...]` per channel.

## Traps already hit (don't reintroduce)

- LibRaw `dng_levels.baseline_exposure` is **−999** when absent → guard it (black images otherwise).
- LibRaw overwrites `sizes.flip` with `user_flip` in `dcraw_process()`: read orientation **before** processing.
- LibRaw ignores DNG opcodes; Pixel DNGs need our gain-map handling (up to 4× corner gain).
- LibRaw sources: exclude `src/*/*_ph.cpp` placeholder files.
- Edge-aware (bilateral) filters in log space: a near-zero channel (blue in saturated yellows) makes every range
  weight underflow; seed the sum with the centre pixel at weight 1, never divide by a sum that can be 0 (skin retouch
  rendered black dots that way).
- Fragment shaders need `precision highp int;` — mediump ints are 16-bit on some phone GPUs.
- Don't name a GLSL variable `texture`.
- Compose slider double-tap: detected from the Slider's own callbacks (`SliderTapTracker`); a pointer
  detector on the slider delayed its taps.
- HaldCLUTs up to 256³: resample from native memory (a float array is ~200 MB of heap).
- MediaPipe failures can be `Error`s (e.g. `ExceptionInInitializerError`), not `Exception`s — catch
  `Throwable` around segmentation and fail softly.
- Blemish removal must exclude facial features (eyes, brows, lips, nostrils via Face Landmarker) and
  very dark spots; nostrils/tear ducts are "skin" to the segmentation model.
- LUT loading: a HaldCLUT parse needs 12–64 MB of **Java heap** (`allocateDirect` is heap on ART), so
  `LutLoader.load` is synchronized and callers must not request a LUT per slider step (dedupe in-flight
  loads; Auto shares one cache with its `PhotoSession`). Heap limit on the emulator is 192 MB.
- Auto exposure targets are in decoded units (1.0 = sensor clip): cameras meter a normal scene's key to
  ~0.06, not 0.18; Auto aims at 0.043 (owner prefers darker, punchier film renders). Phone DNGs (`AutoTone.cameraMetered`:
  Google/Apple/Samsung with BaselineExposure) skip that and keep the phone's exposure, Auto only (Camera meters live).
  Face area counts face-skin confidence > 0.5 only (summing raw confidences gave every photo a ~2% "face"); faces steer
  exposure on a 0.3%→2% ramp. The segmenter still finds "skin" on cats (cat faces measure 1–11%), and the Face
  Landmarker is no filter: it missed most small human faces and found one on a cat. `AutoViewModel` logs each photo's
  stats under tag `RawBoyAuto` for recalibrating; `tools/auto-eval/` measures Auto against darktable edits.
- **Tint direction** follows Lightroom: + Tint = more magenta image, and green light gives a positive
  as-shot tint (`WhiteBalance`: tint is the assumed illuminant's Duv offset, + = above the locus).
  Before 2026-10-10 it was reversed; keep source and target white going through the same conversion.
- Export: `OffscreenDeveloper` takes the **source** image size (`image.width/height`), not the rotated
  output size; the shader samples the 512 px base, bloom and skin mask in source pixels.
- App modules must set `ndkVersion` (or the engine's `.so` ships unstripped, ~9× larger), the same
  `abiFilters`, and `noCompress tflite/task` (packaging happens in the app, not the library).
- Generated shader Kotlin is registered as a **java** source dir: KGP 2.1 ignores AGP's generated kotlin dirs.
- `am start --eu android.intent.extra.STREAM …` neither grants read access nor makes a `SEND_MULTIPLE`
  list; test Auto intake with `VIEW -d … --grant-read-uri-permission` (one intent per file).
- Viewfinder orientation: Camera2 puts the sensor rotation into the SurfaceTexture transform matrix on the Pixel and
  on the emulator, so rotating by `SENSOR_ORIENTATION` as well turned it sideways and stretched it. The shader
  rotates only when the matrix doesn't (`ViewfinderRenderer`, off-diagonal check). Focus regions and face rects
  still map through the sensor orientation (they're in sensor coordinates).
- **Measured auto denoise** (Pro's Auto button, Auto; not Camera): `noise.cpp` `estimateNoise` (JNI
  `RawNative.noiseLevel`, `rb_noise_level`, wasm `noiseLevel`) → `NoiseLevel` (luma, chroma std in the shader's sqrt
  domain) → `AutoTone.measuredNoiseSettings`: slider = 2000 (luma) / 2600 (colour) × std × 2^((exposure + 0.7) / 2),
  sharpening 15 → 5 as that luma value grows, then luma × 0.8 (`LUMA_DENOISE_SCALE`, 2026-10-18: the full fit read too smooth); sets `colorDenoise` explicitly (ISO fallback leaves it null). ~400 patches
  of 84 px, 16 px blocks: luma minus Gaussian σ 1.5, chroma minus σ 6; per 1 EV band the 10th percentile, weighted by
  band area (deep-shadow read noise counts). Checks: a 3×3 residual underestimated coarse (correlated) noise of the
  old Canon G9; a 2×2-binned one read texture on iPhone ProRAW as noise. Calibrated on `.raw/` with 100% crops through
  the web export (`PhotoSession.export` with a crop): 1× right, 1.6× softens fur. Auto's Denoise adjustment shifts
  `colorDenoise` too when set (`LookApplier`).
- Camera denoise: `AutoTone.develop(noiseIsoScale)`; Camera passes 4 (phone sensors ≈ 2 stops noisier than the
  camera raws the ISO table was tuned on) and the capture result's ISO as fallback (DNGs may lack it).
- Performance (camera): the viewfinder's 512 px readback is asynchronous (PBO + fence, collected on a later
  frame); a blocking `glReadPixels` there dropped frames. Shots develop on one thread (`RawBoy-develop`, nice 5)
  that keeps an `ExportGpu` (export EGL context + compiled shader, low GPU priority via
  `EGL_IMG_context_priority`); normal CPU priority (see shot speed below). **Don't use `THREAD_PRIORITY_BACKGROUND`** for it: nice ≥ 10 moves a thread to
  the background cgroup (little cores only). LibRaw is built with OpenMP (`-fopenmp` PUBLIC: `libraw_types.h`
  changes LibRaw's memory-manager layout under `_OPENMP`, so every includer must agree; libomp static).
  `SkinSegmenter` keeps both MediaPipe models loaded (synchronized); close each result's masks (`MPImage`)
  or native memory leaks. Timing logs: `RawBoyCamera` (decode / analysis / develop + JPEG per shot),
  `RawBoyAuto` (small copies, skin detection), `RawBoy` (export develop vs encode, skin sub-steps),
  `RawBoyViewfinder` (fps, GL-thread ms per frame, readback latency every 5 s).
- Camera flash (`FlashMode`, `CameraController.captureDng(flash, flashGuide)`): **the camera's flash AE is useless for
  the raw** (Pixel 9 Pro): the AE precapture with AE_MODE_ON_ALWAYS_FLASH fires no pre-flash for third-party apps
  (FLASH_REQUIRED after 2 frames, ambient values unchanged) and the still keeps the ambient exposure, only the post-raw
  boost drops: 19% of the raw clipped at 0.4 m. So flash stills are manual (AE_MODE_OFF, boost 100, FLASH_MODE_SINGLE,
  1/30 s): gain (s x ISO) = guide x distance² from `LENS_FOCUS_DISTANCE` (`flashExposure`), capped at the ambient gain;
  the guide is learned per lens (`CameraSettings.flashGuides`, `CameraViewModel.calibrateFlash`) from the auto
  develop's exposure, aiming +0.5 EV (dark raws recover, clipped ones don't). **Pixel 9 Pro UW reports LENS_FOCUS_DISTANCE 0 on
  every frame** (lens and logical result, even AF-locked at ~1 m; it claims APPROXIMATE calibration): exactly 0 = unknown,
  not infinity, and then a separate per-lens gain `"<lens>?"` (`FlashGuides.noDistance`, default 4) is used and learned
  (UW converged towards ~18-20 on a room shot). Pixel W: guide ≈ 18 (desk at 0.3 m:
  ISO ~50, 0.01% clipped). Focus-flash = `torchFocus`: AE_MODE_ON + FLASH_MODE_TORCH, 300 ms, then an AF trigger,
  waiting for AF locked on frames **after** the trigger's frame number (`triggerAndWait` logs every AE/AF/flash state
  change). `aeMode`/`flashMode` go through `setCommon`; `endFlashSequence` restores them and cancels AF. Flash stills:
  ZSL off, no viewfinder calibration (the viewfinder never saw the flash).
- Pro's Auto button: `AutoAnalysis.autoBase` (SceneAnalyzer + AutoTone on the session's 512 px base and skin mask,
  `PhotoSession.skinMask()` forces segmentation) → `AutoTone.applyTo` takes only light/colour/detail fields and keeps
  `lut`/`lutAmount`. Same in `ProStore.applyAuto` / `applyAuto` (TS).
- **Camera burst merge** (`CameraSettings.mergeBurst`, `CameraController.burstDng`, `engine/src/main/cpp/burst_merge.*`,
  `raw/BurstNative.kt`): `captureBurst` of N identical still requests (ZSL off, no flash); the raw reader's listener
  hands each image to `burstSink`, which copies it into native memory at once (the reader keeps maxImages 2), and
  results are paired with images by `SENSOR_TIMESTAMP` on the camera thread; each pair calls `setGain` (t × ISO), the
  reference's pair also `setLevels` (dynamic black, `SENSOR_NOISE_PROFILE` averaged over channels; 0 = estimate from
  the frames). **Streaming:** `BurstMerger`'s worker thread merges frames in order as soon as a frame's gain and the
  levels are known, then frees it; `finish()` merges the rest (missing gains = the reference's) and writes the result
  over the reference's samples. Sums: `num_` per raw pixel (window × weight × (sample − black)), `denom_` per 2×2
  block; the window is evaluated per block (sin² at block centres still sums to 1) and the weights are per block, so one
  denominator per block suffices. A clipped or out-of-frame alternate sample adds the reference's value. The merge is
  our own simplified HDR+ (paper only, no GPL code): align on the 2×2 grey copy (pyramid 1/2/4/4, 8 px tiles, last
  level = the merge tiles of 16 grey px at stride 8), integer grey shifts = even raw shifts (CFA phase kept); spatial
  merge of 32 px raw tiles with sin² windows (sum to 1 at half stride; even/odd tile rows in two passes so threads
  never write the same pixel), tile weight from the excess mean squared grey difference over 2σ², pixel weight from the
  3x3 mean difference. Output keeps the raw's integer levels (rounding ≈ 0.3 DN; scaling + patching WhiteLevel would
  keep more). The develop treats it as `effectiveFrames` (1 + Σ mean tile weights) times the light: noise ISO scale
  ÷ that. Host test: `tools/burst_merge/run.sh`. Pixel 9 Pro (selfie, ISO 1212): noise ~3.2× lower (a little over √8:
  the pixel weights smooth slightly), all 8 frames weight ~1.0, shutter to DNG 2–3.5 s (single 0.3 s), JPEG sooner
  than a single frame (denoise 51 → 16). The first, non-streaming version peaked at ~400 MB native (all 8 raw copies,
  7 grey planes, an accumulator) and Android killed ~20 background apps; streaming peaks at ~210 MB on the host
  (reference 25 + num 50 + denom 12 + grey pyramids + the frame being merged + ~2 waiting), with the wait after the
  last frame 2.0 → 0.4 s. TSan/ASan clean on the host test. Phone (streaming): no apps
  killed, but 6–7 frames still wait (a frame every 33 ms, a merge step 0.3–0.4 s); peak ~590 MB native came from two
  bursts overlapping a develop (a single develop alone ~445 MB), so `CameraViewModel.merging` blocks the shutter until
  a burst's DNG is written (not the develop after it; merge off isn't blocked). With it: merged shots back to back
  peak at 511 MB native (a burst merging while the previous JPEG develops), single shots 455 MB, a merged shot alone
  ~320 MB; no apps killed, 2.1–3.1 s to DNG. Next if needed: request the burst in groups of 2–3 frames to cap the queue.
- Camera focus lock: `CameraController.focusPoint`; `setCommon` puts AF_MODE_AUTO + regions on **every** request
  while locked, including the still capture (with CONTINUOUS_PICTURE there, the camera refocused at the shutter).
- Selfie camera: front lenses with RAW only. Viewfinder mirrored (`Frame.mirror`; the shader mirrors only when the
  SurfaceTexture matrix doesn't already: its 2×2 determinant is < 0 without a mirror). Tap regions and face rects map
  through `uprightToBuffer`/`bufferToUpright(mirrored = true)`. Saved mirrored through the DNG's EXIF orientation:
  rotation r = (sensor − device + 360) % 360, then flip → 0:2, 90:5 (transpose), 180:4, 270:7 (transverse); checked
  on the emulator's front camera (JPEG matches the viewfinder) and on the Pixel 9 Pro, upright and landscape. Calibration keys are per side (`F` + label).
- Pixel 9 Pro selfie camera: RAW 3440×2448 / 3840×2736 (1.40:1), but no preview size with that aspect (all 4:3,
  16:9, 1:1). Lens discovery once required an exact-aspect preview and silently dropped the front camera. Now the
  closest aspect is used; the ISP shows its centre crop, so `Info.previewCropX/Y` map tap regions and faces between
  preview and sensor, and the viewfinder's full size is the raw cropped to the preview aspect (`viewfinderWidth/Height`).
  `infoFor` logs why a camera is rejected. Pixel lenses: back logical 0 (UW 0/3, T 0/5, T2 0/4, T3 0/6), front
  logical 1 (physicals 1/7, 1/8). Check a phone's cameras with `adb shell dumpsys media.camera`.
- Smaller export: `ExportSettings.maxLongSide` → `export/BandDownscaler.kt` (exact area average, streamed per band;
  native in `cpp/band_ops.cpp` with the tile repack, in librawboyjpeg; host-tested against a whole-image resize:
  ±0.51 of a 16-bit level). The grain gets `minGrainPx` = full/out
  long side, so a grain cell is at least one output pixel. The `export WxH` log line shows the full render size.
- TIFF export: `TiffWriter` (Kotlin/Swift/TS, byte-identical output) writes LZW + Predictor 2 strips after a gap and
  the header last, so it needs a seekable output (Android: the MediaStore entry opened `"rw"` as a `FileChannel`; iOS:
  `FileHandle.seek`; web: Blob parts). LZW code widths follow libtiff's "early change" (a code is one bit wider once
  the next free code reaches 2^n; the EOI accounts for the entry the decoder adds on the last code). Its IFD0 is laid
  out by `ExifWriter.tiffIfds` (Kotlin/Swift/TS), which adds the photo's EXIF (Exif and GPS IFDs) after it, the same
  tags JPEGs get; Android reads them from the source with ExifInterface (`ExifMetadata.from`), which can't write
  TIFFs. Auto's **Export as** (format, quality, bit depth, size) lives in each Auto's view model/store, not in the
  shared `ExportSettingsStore`.
- **Preview tone curve (Pixel):** `TONEMAP_AVAILABLE_TONE_MAP_MODES` = CONTRAST_CURVE, FAST, HIGH_QUALITY only (no
  preset/gamma); asking for nothing left the Pixel's own scene-dependent tone mapping on the preview (result
  `TONEMAP_MODE` 1), which no gain can undo. Now an explicit 65-point sRGB `TONEMAP_CURVE` (result mode 0); the
  `shutter` log line shows the applied mode.
- **Viewfinder framing (Pixel):** the request templates default `CONTROL_VIDEO_STABILIZATION_MODE` to ON, which crops
  the preview ~20% (the raw isn't stabilized); `setCommon` sets it OFF. `DISTORTION_CORRECTION_MODE` FAST stays: the DNG
  carries the same correction as an OpcodeList3 warp, which the develop applies. Check the live request with
  `dumpsys media.camera` ("Last request sent").
- **Viewfinder colour (Pixel 9 Pro):** two errors, found by fitting the viewfinder's 512 px linear copy against the raw's
  (same shot, centre cells, 3x3 least squares). (1) The preview buffer is labelled sRGB (data space 0x8810000), so the
  Mali driver decodes its Y'CbCr as BT.709, but the ISP encodes BT.601: reds ~20% too saturated. The viewfinder samples
  the raw Y'CbCr (`GL_EXT_YUV_target`, Mali-G715 has it) and converts BT.601 itself (`LinearizeProgram.yuvToRgb`);
  don't trust the data space's standard. (2) The ISP's own colour matrix (result `COLOR_CORRECTION_TRANSFORM`, after
  `COLOR_CORRECTION_GAINS` = 1/neutral) is more saturated than the DNG's: `CameraController.previewToRec2020` undoes
  both per frame and applies the raw decoder's matrix (LibRaw rgb_cam: D65 ColorMatrix x CameraCalibration, rows
  normalised, inverted) → `Frame.color`. Both are needed (either alone leaves large cross-channel terms). Afterwards
  per-channel gains match within a few %.
- **Camera black:** the ISP renders black ~1.4 DN (of 959) deeper than the DNG's BlackLevel, which is the frame's own
  `SENSOR_DYNAMIC_BLACK_LEVEL` (63.96; the still log prints both). Without matching it the photo's deepest shadows
  were lifted and, after the WB's ~2x blue gain, bluish. Camera decodes with `RawImage.open(extraBlack =
  EXTRA_BLACK)` (fraction of the white range, subtracted in float in `decodeWithLibRaw`); Pro/Auto pass 0. Not
  LibRaw's clipping of noise below black (a pedestal before black subtraction changed nothing) nor black rounding.
  Measured at ISO 170-250 only; if high-ISO shadows differ, check whether it scales with ISO.
- **As-shot white of DNGs from cameras LibRaw doesn't list (phones):** `color.cam_xyz` only comes from LibRaw's
  table, so it was zero and the as-shot white fell back to D65 (a 2850 K tungsten Pixel shot read as 6500 K; the auto
  WB then went elsewhere than the viewfinder's). `estimateAsShotWhite` uses the DNG's D65 ColorMatrix x
  CameraCalibration (`dngCamFromXyz`), the matrix LibRaw's colour conversion picks, times **AnalogBalance**: Apple
  ProRAW (Linear Raw, already white balanced) writes AsShotNeutral 1 1 1 and keeps the as-shot white only in
  AnalogBalance; without it iPhone shots read xy (0.36, 0.24), far below the locus. ProRAW's ProfileGainTableMap
  (Apple's local tone map) and semantic masks are not used.
- **Viewfinder gamut:** the Pixel's preview is clipped to sRGB in the ISP (no `COLOR_SPACE_PROFILES`, no negative
  Y'CbCr values), so colours beyond sRGB (deep orange under tungsten) show less saturated and pinker than in the
  photo. Undoing that needs `COLOR_CORRECTION_MODE_TRANSFORM_MATRIX` with a wide-gamut matrix, which needs AWB off
  (our own white balance). The preview also runs Google's HDRnet (`dumpsys media.camera` use case), not switchable.
- **Viewfinder calibration** (`previewScales3`, per side + lens): each lens's preview has its own gain vs the raw
  (Pixel 9 Pro: selfie ≈ 0.5, W ≈ 0.5–0.8, T3 ≈ 3.8). The first shot on a lens takes the full correction, later ones
  move halfway (in stops), estimated against the gain in use at that shot's shutter (back-to-back shots). Checked on the
  phone: second shot within 1% (FW 0.995, T3 1.007). Same-scene shots vary ±0.3 stop (scene, AE).
- **Low light:** preview AE target fps [30, 30] (viewfinder 31 fps indoors instead of 18); the still request uses
  AE with [15, 30] and the camera itself moves its post-raw boost into exposure time (preview 1/30 s ISO 1410 boost
  2.39 → still 1/15 s ISO 1410 boost 1.2): raws as clean as before. The viewfinder predicts that with
  `CameraController.stillGain` (min(1/15 s ÷ preview time, boost) when the preview sits at the cap). A manual-exposure
  still keeping t×ISO was tried first and came out a stop dark (boost isn't in the raw) — don't.
- **Shot speed (Pixel 9 Pro, W, warm, debug build):** 4.4 s → 2.8–3.0 s; selfie ≈ 2.1 s. Per stage now: capture +
  DNG write 0.35, LibRaw unpack 0.07 + AHD demosaic 0.7–0.9 (OpenMP), convert + orient 0.33 (orientation fused into the
  conversion; ARM64 hardware float→half, bit-identical), small copies 0.2, skin detection 0 when the camera saw no face
  (else ~0.45 warm), GPU develop 0.5 (16-bit readback: the Pixel's preferred RGBA_INTEGER/UNSIGNED_SHORT), native
  downscale 0.2, jpegli 0.2. Develop thread at normal priority (nice 5 gained nothing for the viewfinder: its GL
  thread needs ~1.7 ms/frame).
- Camera grain: `ShaderParams.from(grainSizeScale = 0.5, grainStrength = …)` (defaults 1 elsewhere). Half-size
  grain is mostly averaged away by the 3000 px area downscale (simulated with the shader's grain: std 0.83 -> 0.62), so
  Camera raises it ×1.34 for small photos, ×1 at full size (point-sampled, no loss). `uGrainSize` = geometry grain size
  × scale, so the 1-output-pixel floor halves too.
- Camera metering: `SceneAnalyzer.analyze(meter = MeterArea.Centre(0.5))` takes `key` and `p98` (what sets exposure)
  from the centre box only; other stats stay whole-frame (null = Auto's centre-weighted whole frame). Camera2 AE gets
  the same box as `CONTROL_AE_REGIONS`. While focus is locked by a tap both meter `MeterArea.Spot` (16% of the short
  side, shifted inside the frame): the live develop at the viewfinder point, the shot at that point turned by the
  device rotation (`Spot.turned`; iOS reads `UIDevice.orientation`), since the photo is level and the viewfinder is
  portrait. The front camera's preview crop isn't applied to the shot's spot (a few % off). Ports: `MeterArea.kt/.swift/.ts`.
- Denoise coarse stage: `uDenoiseWide` = (denoise − 65) / 35 (`ShaderParams.wideDenoise`); luma 5×5 at stride 3,
  chroma 7×7 at stride 6 (18 px), range weights against the fine result. The sparse stages' taps are **jittered** per
  pixel (`jitteredTap`: a random point of each cell, hashed from the full-res pixel, centre tap fixed, cells shrunk to
  keep the old radius): on a fixed lattice the leftover chroma repeated every 6 px (blocky high-ISO colour noise).
  After the 18 px chroma stage, a 7×7 stage on `uBase` (512 px, ~16 px/texel on 50 MP; stride 1: stride 2 drew a grid) takes the coarsest colour
  blotches (skipped when `uBaseTexel` is 1, i.e. no base bound); chroma only.
  Luma and chroma are separate
  (`uDenoise`/`uDenoiseWide` vs `uColorDenoise`/`uColorDenoiseWide`): `DevelopParams.colorDenoise` is nullable, null =
  follows `denoise` (`effectiveColorDenoise`), so Auto, Camera, AutoTone and pre-split edits/presets need no change; only
  Pro's Color denoise slider sets it (moving Luma pins it). A Detail preset without `colorDenoise` decodes it as null.
  With both equal the shader output is the old single denoise. Needs `WIDE_DENOISE_MARGIN` (20) source
  pixels: `ShaderParams.spatialMargin` and PhotoSession's zoom margin include it. Camera's high-ISO curve
  (`highIsoDenoise`, by ISO × noise scale) pushes up to 100.
- `ViewfinderRenderer`: pass 1 and the readback must run even before the first params arrive (the first params
  come from the first readback); only pass 2 waits for params.
- Compose text on a plain black `Box` is black (no `Surface` → default content colour): Camera wraps its screen in a `Surface`.
- **Hot pixels** (`removeHotPixels` in `decoder_libraw.cpp`, between `unpack()` and `dcraw_process()`, on
  `rawdata.raw_image`; DNG SDK path and linear raws untouched). Traps found tuning it on `DSCF3079.RAF` (GFX 50R, ISO
  6400) and the Canon G9 CR2s: LibRaw numbers the second green 3 — merge it with 1 or diagonal hair detail looks hot;
  compare spill into adjacent photosites white balanced (raw R/B ≈ half of G) with the noise margin scaled too; the
  spill background must be the ring's lower median (a 2–3 px specular lights the ring); allow exactly one raised
  neighbour (GFX warm pixels are mostly vertical pairs) — a lower-median spill rule ate asymmetric earring highlights.
  Check: fixes repeat at the same sensor positions across shots from one body (109 shared between the two GFX files).
  The log line `LibRaw decode: … hot pixels N in M ms` (tag `RawBoy`) shows the count.
- **Column defects** (`removeColumnDefects`, `decoder_libraw.cpp`, before `removeHotPixels`): Bayer only. Pentax 645D
  (`.raw/pentax/`, in-camera DNG 1.1, no opcodes, so the DNG SDK wouldn't fix them either) has two red half-columns,
  visible x 2347 from row 715 and x 4195 from row ~1881, +17–19 DN to the bottom edge. Its masked margins (raw x < 48,
  > 7376) are full of "defective" columns; they're outside the visible area. Columns two away read ≈ −offset/2 (their
  reference includes the bad one): the ghost check drops them. The run's ends are refined to the row (a block-level
  start missed ~150 rows).
- DNG SDK build: XMP toolkit is omitted; `dng_jxl.cpp` is patched at configure time to guard two XMP uses.

## Conventions

- Match surrounding code: KDoc on public types, short comments explaining *why*. No new dependencies
  without a reason; prefer extending existing modules.
- Slider values live in `DevelopParams` in user units (−100..100 etc.); conversion to shader units is in
  `ShaderParams.from`. Every new slider needs: default, Controls entry, ShaderParams mapping, and a decision
  on carry-over (edits carry to the next photo; rotation/straighten/crop don't; WB only if changed), and on
  presets (add it to a `PresetGroup` in `Presets.kt` and `Presets.ts`, plus the field mapping in `Presets.kt`).
- Defaults must leave the image untouched (identity), and resets via double-tap must work.
- GPL projects (darktable, spektrafilm) are inspiration only for the standard edition: reimplement the method,
  never copy code. **RawBoy Film is GPL-3.0-or-later** (`licenses/GPL-3.0.txt`): spektrafilm code may be ported, but
  only into film-edition-only places (`shaders/film/`, `tools/spektrafilm_luts/`, `app-camera/src/film/`), each file
  naming the spektrafilm file and commit it follows, with an `SPDX-License-Identifier: GPL-3.0-or-later` header.
  Never let ported code reach `src/main` or the shared shaders: standard apps must stay GPL-free (check the APKs).
  Shared hooks (DevelopParams.film*, uniforms, the `applyFilm`/`filmOptics` calls, FilmStocks/FilmLooks,
  `film_form.glsl`) are our own code.
  RawBoy Film's complete source (engine + camera) is this public repo; keep it buildable.
- LibRaw is used under the CDDL 1.0 (RawBoy Film: LGPL 2.1, GPL-compatible), unmodified: never patch its sources (a
  modified LibRaw file would have to be published); work around it in our own files, as `decoder_libraw.cpp` does.
- No Adobe DNG SDK in RawBoy Film (CMake `RAWBOY_USE_DNG_SDK=OFF` from the engine's film flavour): its licence and
  Adobe's DNG patent licence aren't GPL-compatible. The web has no film edition (Auto's Film mode uses film_form.glsl).
- spektrafilm LUTs are CC BY-SA 4.0: keep `luts/spektrafilm/SPEKTRAFILM_LICENSE.txt` unmodified and the LUT creator's
  `.cube` headers intact, record RawBoy's changes in `CHANGELOG.txt` (written by the generator), and don't use the
  name "spektrafilm" in app branding (factual credit only, as Camera's Settings does).
- Update `Readme.md` (controls table + changelog) with each user-visible change. The owner commits.
- `UI.md` is the backlog of UI proposals not done yet (from the 2026-10-17 review); remove an item when it lands.

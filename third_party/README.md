# third_party

## Adobe DNG SDK (JPEG XL / DNG 1.7 support)

DNGs that LibRaw can't read — most importantly **DNG 1.7 with JPEG XL compression**
(e.g. Adobe DNG Converter's "JPEG XL" option, newer phones) — are decoded with
the Adobe DNG SDK. It isn't committed (`dng_sdk/` is git-ignored, ~80 MB); download it before building:

```
curl -LO https://download.adobe.com/pub/adobe/dng/dng_sdk_1_7_1.zip
unzip dng_sdk_1_7_1.zip
mv dng_sdk_1_7_1 <repo>/third_party/dng_sdk
```

so that `third_party/dng_sdk/dng_sdk/source/dng_host.cpp` and
`third_party/dng_sdk/libjxl/libjxl/CMakeLists.txt` exist, then re-sync Gradle.
Release builds need it: Android `*Release` builds and RawBoy Web's `npm run build:public` fail without it (the
Docker build downloads it itself). Debug and local web builds still work without it; JPEG XL DNGs then fail to open.

## Licence

- `DNG_SDK_LICENSE.txt`: the DNG SDK License Agreement (the zip has no licence file; the SDK's sources refer to it).
- Everything shipped, including the DNG SDK, its libjxl/Brotli/Highway/skcms and the DNG patent license, is in
  `../licenses/THIRD_PARTY_NOTICES.txt`, which every app and RawBoy Web show. The DNG patent license requires the
  notice "This product includes DNG technology under license by Adobe." in the source code and documentation
  (it's in `decoder_dng.cpp`, the Readme and the notices). Don't use the Adobe or DNG logos without Adobe's
  permission.

How it's built (`engine/src/main/cpp/dng_sdk.cmake`, shared by Android and RawBoy Web's `web/native`):

- libjxl: the copy bundled with the SDK (`jxl-static`, `jxl_threads-static`).
- Read-only, without the XMP toolkit (`qDNGUseXMP=0`): `dng_validate`, `dng_xmp_sdk`
  and `dng_update_meta` are left out, and two unguarded XMP uses in `dng_jxl.cpp`
  are wrapped in `#if qDNGUseXMP` in a patched copy at configure time (the build
  stops with a clear error if a future SDK version changes that code).
- Lossy (8-bit JPEG) DNG is not enabled yet (`qDNGUseLibJPEG=0`); the SDK bundles
  libjpeg if that's needed later.

Decoder order: LibRaw first (camera raws and most DNGs, with RawBoy's own lens
corrections), Adobe DNG SDK as the fallback for DNGs LibRaw rejects. With the SDK
path the SDK applies DNG opcodes itself, so the Lens tab's profile switch is off.

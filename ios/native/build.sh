#!/bin/sh
# Builds the engine's native code for iOS into ios/native/out/:
#   RawBoyNative.xcframework  decoders (LibRaw, + Adobe DNG SDK when third_party/dng_sdk exists)
#   RawBoyJpeg.xcframework    jpegli + export band operations
#   RawBoyNativeFilm.xcframework  the decoders for RawBoy Film (GPLv3): LibRaw only, never the Adobe DNG SDK;
#                                 only where shaders/film exists (RawBoy Film's own repository)
# Each holds an iPhone (arm64) and a Simulator (arm64) slice. Needs Xcode and CMake; the first run downloads
# LibRaw and jpegli. Usage: ios/native/build.sh
set -e
cd "$(dirname "$0")"

# xcode-select may point at the Command Line Tools, which have no iOS SDK.
if ! xcrun --sdk iphoneos --show-sdk-path > /dev/null 2>&1 && [ -d /Applications/Xcode.app ]; then
    export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
fi

DEPLOYMENT_TARGET=17.0
OUT=out
JOBS=$(sysctl -n hw.ncpu)

build() {  # build <sdk>
    sdk=$1
    dir=build/$sdk
    # libjxl's bundled dependencies declare CMake < 3.5 policies, which CMake 4 refuses by default.
    cmake -S . -B "$dir" -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_SYSROOT="$sdk" -DCMAKE_OSX_ARCHITECTURES=arm64 \
        -DCMAKE_OSX_DEPLOYMENT_TARGET=$DEPLOYMENT_TARGET -DCMAKE_POLICY_VERSION_MINIMUM=3.5 > /dev/null
    cmake --build "$dir" -j "$JOBS" --target rawboy_native rawboy_jpeg
    for name in RawBoyNative RawBoyJpeg; do
        framework "$dir" "$name" "$sdk"
    done
}

build_film() {  # build_film <sdk>: rawboy_native for RawBoy Film, without the DNG SDK, as RawBoyNativeFilm
    sdk=$1
    dir=build/film-$sdk
    cmake -S . -B "$dir" -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_SYSROOT="$sdk" -DCMAKE_OSX_ARCHITECTURES=arm64 \
        -DCMAKE_OSX_DEPLOYMENT_TARGET=$DEPLOYMENT_TARGET -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
        -DRAWBOY_USE_DNG_SDK=OFF -DRAWBOY_NATIVE_NAME=RawBoyNativeFilm > /dev/null
    cmake --build "$dir" -j "$JOBS" --target rawboy_native
    framework "$dir" RawBoyNativeFilm "$sdk"
}

framework() {  # framework <build dir> <name> <sdk>: wraps the dylib as <build dir>/frameworks/<name>.framework
    fw=$1/frameworks/$2.framework
    rm -rf "$fw"
    mkdir -p "$fw/Headers" "$fw/Modules"
    cp "$1/$2" "$fw/$2"
    strip -x "$fw/$2"
    # The linker's ad-hoc signature names the binary "<name>-<hash>"; iOS refuses to install an embedded framework
    # whose signing identifier doesn't match its bundle id (Xcode keeps the identifier when it re-signs).
    codesign --force --sign - --identifier "app.rawboy.native.$2" "$fw/$2" 2> /dev/null
    header=$(echo "$2" | sed 's/RawBoyNativeFilm/rawboy_native/; s/RawBoyNative/rawboy_native/; s/RawBoyJpeg/rawboy_jpeg/').h
    cp "$header" "$fw/Headers/"
    cat > "$fw/Modules/module.modulemap" << EOF
framework module $2 {
    header "$header"
    export *
}
EOF
    platform=iPhoneOS
    [ "$3" = iphonesimulator ] && platform=iPhoneSimulator
    cat > "$fw/Info.plist" << EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key><string>$2</string>
    <key>CFBundleIdentifier</key><string>app.rawboy.native.$2</string>
    <key>CFBundleName</key><string>$2</string>
    <key>CFBundlePackageType</key><string>FMWK</string>
    <key>CFBundleShortVersionString</key><string>1.0</string>
    <key>CFBundleVersion</key><string>1</string>
    <key>CFBundleSupportedPlatforms</key><array><string>$platform</string></array>
    <key>MinimumOSVersion</key><string>$DEPLOYMENT_TARGET</string>
</dict>
</plist>
EOF
}

FILM=0
[ -d ../../shaders/film ] && FILM=1

build iphoneos
build iphonesimulator
if [ "$FILM" = 1 ]; then
    build_film iphoneos
    build_film iphonesimulator
fi

mkdir -p "$OUT"
xcframework() {  # xcframework <name> <build dir prefix>
    rm -rf "$OUT/$1.xcframework"
    xcodebuild -create-xcframework \
        -framework "build/${2}iphoneos/frameworks/$1.framework" \
        -framework "build/${2}iphonesimulator/frameworks/$1.framework" \
        -output "$OUT/$1.xcframework" > /dev/null
}
xcframework RawBoyNative ""
xcframework RawBoyJpeg ""
if [ "$FILM" = 1 ]; then xcframework RawBoyNativeFilm film-; fi
ls -la "$OUT"

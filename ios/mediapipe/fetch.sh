#!/bin/sh
# Downloads MediaPipe Tasks for iOS (the prebuilt frameworks the CocoaPods MediaPipeTasksVision / MediaPipeTasksCommon
# pods point at; no CocoaPods needed) into ios/mediapipe/out/. URLs are from the pods' podspecs (CocoaPods/Specs).
# Usage: ios/mediapipe/fetch.sh
set -e
cd "$(dirname "$0")"
VERSION=0.10.33
COMMON_URL=https://dl.google.com/cpdc/20260319-173533/MediaPipeTasksCommon-$VERSION.tar.gz
VISION_URL=https://dl.google.com/cpdc/20260319-173539/MediaPipeTasksVision-$VERSION.tar.gz

fetch() {  # fetch <name> <url>
    if [ -d "out/$1/frameworks" ]; then return; fi
    rm -rf "out/$1"
    mkdir -p "out/$1"
    curl -fL --retry 3 -o "out/$1.tar.gz" "$2"
    tar -xzf "out/$1.tar.gz" -C "out/$1"
    rm "out/$1.tar.gz"
}

# The shipped xcframeworks' Info.plist describes a static library with a Headers folder, but they hold .framework
# folders (CocoaPods copes, Xcode doesn't): rebuild each xcframework from the frameworks inside.
repack() {  # repack <name>
    xc=out/$1/frameworks/$1.xcframework
    [ -f "$xc/.repacked" ] && return
    args=""
    for slice in "$xc"/*/; do args="$args -framework ${slice}$1.framework"; done
    xcodebuild -create-xcframework $args -output "out/$1/$1.xcframework" > /dev/null
    rm -rf "$xc"
    mv "out/$1/$1.xcframework" "$xc"
    touch "$xc/.repacked"
}

if ! xcrun --sdk iphoneos --show-sdk-path > /dev/null 2>&1 && [ -d /Applications/Xcode.app ]; then
    export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
fi
fetch MediaPipeTasksCommon "$COMMON_URL"
fetch MediaPipeTasksVision "$VISION_URL"
repack MediaPipeTasksCommon
repack MediaPipeTasksVision
ls out/*/frameworks

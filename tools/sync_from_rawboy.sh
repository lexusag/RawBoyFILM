#!/bin/sh
# Copies the files RawBoy Film shares with RawBoy from a RawBoy checkout into this repository.
# Film-only files and this repository's own build setup are left alone (see the excludes below).
# Usage: tools/sync_from_rawboy.sh <path to a RawBoy checkout>; then review `git status` / `git diff`, build, commit.
set -e
src=${1:?usage: tools/sync_from_rawboy.sh <path to a RawBoy checkout>}
[ -d "$src/engine" ] && [ -d "$src/app-camera" ] || { echo "$src is not a RawBoy checkout" >&2; exit 1; }
cd "$(dirname "$0")/.."

# Shared paths, mirrored with --delete so files removed in RawBoy go here too.
for path in engine app-camera/src/main app-camera/src/standard shaders luts gradle ios/Engine ios/Camera \
    ios/EngineTests ios/native ios/shaders ios/mediapipe ios/icons tools/film_luts tools/burst_merge third_party; do
    rsync -a --delete \
        --exclude '/film/' --exclude 'build/' --exclude '.cxx/' --exclude 'out/' --exclude 'dng_sdk/' \
        --exclude 'spektrafilm/' \
        "$src/$path/" "$path/"
done
# The spektrafilm LUTs are generated here (tools/spektrafilm_luts); RawBoy bundles a copy of them.
# Single shared files.
for file in build.gradle.kts gradle.properties gradlew gradlew.bat .gitignore \
    app-camera/build.gradle.kts app-camera/proguard-rules.pro licenses/THIRD_PARTY_NOTICES.txt; do
    [ -f "$src/$file" ] && cp "$src/$file" "$file"
done
echo "Synced from $src. Review with: git status && git diff"

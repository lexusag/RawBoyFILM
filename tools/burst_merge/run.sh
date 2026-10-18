#!/bin/sh
# Builds and runs the burst merge host test (any C++17 compiler).
set -e
here=$(cd "$(dirname "$0")" && pwd)
cpp="$here/../../engine/src/main/cpp"
out="${TMPDIR:-/tmp}/burst_merge_test"
${CXX:-c++} -std=c++17 -O2 -pthread -I"$cpp" "$here/test.cpp" "$cpp/burst_merge.cpp" -o "$out"
time "$out"

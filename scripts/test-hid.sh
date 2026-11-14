#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
build_dir="${TMPDIR:-/tmp}/gyrolab-hid-tests-$$"
trap 'rm -rf "$build_dir"' EXIT HUP INT TERM
mkdir -p "$build_dir"
find app/src/hidTest -name '*.java' > "$build_dir/sources.txt"
printf '%s\n' app/src/main/java/io/gyrolab/app/HidTransport.java >> "$build_dir/sources.txt"
javac --release 8 -d "$build_dir" @"$build_dir/sources.txt"
java -cp "$build_dir" io.gyrolab.app.HidTransportTests

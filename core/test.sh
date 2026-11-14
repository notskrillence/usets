#!/usr/bin/env bash
set -eu
cd "$(dirname "$0")"
mkdir -p build/test-classes
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" --release 8 -d build/test-classes src/main/java/io/gyrolab/core/*.java src/test/java/io/gyrolab/core/*.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp build/test-classes io.gyrolab.core.CoreTests

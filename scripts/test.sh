#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/tests/{jvm,udp,config,deps}
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
"$JAVAC" --release 8 -d build/tests/jvm core/src/main/java/io/gyrolab/core/*.java core/src/test/java/io/gyrolab/core/*.java lab/src/main/java/io/gyrolab/lab/*.java lab/src/test/java/io/gyrolab/lab/*.java
"$JAVA" -cp build/tests/jvm io.gyrolab.core.CoreTests
"$JAVA" -cp build/tests/jvm io.gyrolab.lab.LabTest
PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$PATH" ./scripts/test-lab-view.sh
"$JAVAC" --release 8 -d build/tests/udp app/src/udpTest/android/os/*.java app/src/udpTest/java/*.java app/src/main/java/io/gyrolab/app/UdpTransport.java
"$JAVA" -cp build/tests/udp UdpTransportSmoke
${CC:-cc} -std=c11 -Wall -Wextra -Werror -O2 companion/protocol_test.c -o build/tests/protocol-test
build/tests/protocol-test
SDK_ROOT=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
if [ -z "$SDK_ROOT" ] || [ ! -f "$SDK_ROOT/platforms/android-35/android.jar" ]; then
  echo 'Core, lab, UDP and protocol checks passed. Set ANDROID_SDK_ROOT with SDK35 to run preset checks.'
  exit 0
fi
JSON_JAR=build/tests/deps/json-20240303.jar
if [ ! -f "$JSON_JAR" ]; then
 curl -fLsS --max-time 60 -o "$JSON_JAR" https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar
fi
printf '%s  %s\n' 3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed "$JSON_JAR" | sha256sum -c -
"$JAVAC" --release 8 -cp "build/tests/jvm:$JSON_JAR:$SDK_ROOT/platforms/android-35/android.jar" -d build/tests/config app/src/main/java/io/gyrolab/app/ConfigStore.java app/src/configTest/io/gyrolab/app/*.java
"$JAVA" -cp "build/tests/config:build/tests/jvm:$JSON_JAR:$SDK_ROOT/platforms/android-35/android.jar" io.gyrolab.app.ConfigStoreCheck
if [ -x scripts/test-hid.sh ]; then PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$PATH" ./scripts/test-hid.sh; fi
printf 'Software test suites passed. Android/iPad/Windows hardware behavior is a separate check.\n'

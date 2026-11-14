#!/usr/bin/env bash
# Optional actual Android runtime smoke. This is distinct from all JVM doubles tests.
set -euo pipefail
PROJECT_ROOT=$(cd "$(dirname "$0")/.." && pwd)
SDK_ROOT=${ANDROID_SDK_ROOT:-"$PROJECT_ROOT/../toolchain/android"}
EMULATOR_WORK=${EMULATOR_WORK:-"$PROJECT_ROOT/../emulator2"}
APK=${1:-"$PROJECT_ROOT/app/build/outputs/apk/debug/app-debug.apk"}
IMAGE=${EMULATOR_IMAGE:-'system-images;android-30;google_atd;x86_64'}
AVD=${EMULATOR_AVD:-GyroLab_API30_ATD_Software}
PORT=${EMULATOR_PORT:-5582}
SERIAL="emulator-$PORT"
export ANDROID_SDK_ROOT="$SDK_ROOT"
export ANDROID_AVD_HOME="$EMULATOR_WORK/avd"
export ANDROID_USER_HOME="$EMULATOR_WORK/android-user"
export JAVA_HOME=${JAVA_HOME:-"$PROJECT_ROOT/../toolchain/jdk-21.0.13+9"}
mkdir -p "$ANDROID_AVD_HOME" "$ANDROID_USER_HOME" "$EMULATOR_WORK/results"
ADB="$SDK_ROOT/platform-tools/adb"
EMU="$SDK_ROOT/emulator/emulator"
IMAGE_PATH=$(printf '%s' "$IMAGE" | tr ';' '/')
if [ ! -e "$SDK_ROOT/$IMAGE_PATH/system.img" ] || [ ! -x "$EMU" ]; then
  printf 'Install emulator and %s using sdkmanager first.\n' "$IMAGE" >&2
  exit 2
fi
if [ ! -f "$ANDROID_AVD_HOME/$AVD.ini" ]; then
  printf 'no\n' | "$SDK_ROOT/cmdline-tools/latest/bin/avdmanager" create avd --name "$AVD" --package "$IMAGE" --path "$ANDROID_AVD_HOME/$AVD.avd" --force
  cat >> "$ANDROID_AVD_HOME/$AVD.avd/config.ini" <<'AVD_CONFIG'
hw.lcd.width=480
hw.lcd.height=960
hw.lcd.density=160
hw.ramSize=1536
hw.cpu.ncore=1
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader_indirect
hw.keyboard=yes
hw.gyroscope=yes
hw.accelerometer=yes
hw.audioInput=no
hw.audioOutput=no
disk.dataPartition.size=512M
skin.name=480x960
skin.path=480x960
AVD_CONFIG
fi
sed -i '/^disk\.dataPartition\.size[[:space:]]*=/d' "$ANDROID_AVD_HOME/$AVD.avd/config.ini"
printf 'disk.dataPartition.size=512M\n' >> "$ANDROID_AVD_HOME/$AVD.avd/config.ini"
# Optional space-efficient task-local userdata for constrained software test machines.
# This formats a NEW image only; existing Android user data is never overwritten.
if [ "${EMULATOR_SPARSE_USERDATA:-0}" = 1 ] && [ ! -e "$ANDROID_AVD_HOME/$AVD.avd/userdata-qemu.img" ]; then
  cp --sparse=always "$SDK_ROOT/$IMAGE_PATH/userdata.img" "$ANDROID_AVD_HOME/$AVD.avd/userdata-qemu.img"
  truncate -s 6442450944 "$ANDROID_AVD_HOME/$AVD.avd/userdata-qemu.img"
  MKE2FS_CONFIG="$SDK_ROOT/platform-tools/mke2fs.conf" "$SDK_ROOT/platform-tools/mke2fs" -F -t ext4 -O encrypt -L data "$ANDROID_AVD_HOME/$AVD.avd/userdata-qemu.img"
fi
"$ADB" start-server >/dev/null
EMU_PID=''
SMOKE_PASSED=0
cleanup() {
  if { [ "${KEEP_EMULATOR:-0}" != 1 ] || [ "$SMOKE_PASSED" != 1 ]; } && [ -n "$EMU_PID" ]; then
    timeout 5 "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1 || kill "$EMU_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT
if ! "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1; then
  "$EMU" -avd "$AVD" -port "$PORT" -accel off -gpu swiftshader_indirect -feature -Vulkan -no-window -no-audio -no-boot-anim -no-snapshot -memory 1536 -cores 1 -partition-size 512 -camera-back none -camera-front none -show-kernel > "$EMULATOR_WORK/emulator.log" 2>&1 &
  EMU_PID=$!
fi
START=$SECONDS
BOOTED=0
while (( SECONDS - START < 300 )); do
  if [ -n "$EMU_PID" ] && ! kill -0 "$EMU_PID" 2>/dev/null; then
    echo 'Emulator exited before Android boot; see emulator.log.' >&2
    tail -n 40 "$EMULATOR_WORK/emulator.log" >&2
    exit 3
  fi
  BOOT=$(timeout 8 "$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
  if [ "$BOOT" = 1 ]; then BOOTED=1; break; fi
  sleep 2
done
if [ "$BOOTED" != 1 ]; then
  echo 'Software-only Android boot did not complete within300 seconds.' >&2
  timeout 8 "$ADB" -s "$SERIAL" logcat -d > "$EMULATOR_WORK/results/boot-logcat.txt" 2>&1 || true
  exit 4
fi
"$ADB" -s "$SERIAL" shell input keyevent 82
"$ADB" -s "$SERIAL" shell settings put global window_animation_scale 0
"$ADB" -s "$SERIAL" shell settings put global transition_animation_scale 0
"$ADB" -s "$SERIAL" shell settings put global animator_duration_scale 0
"$ADB" -s "$SERIAL" logcat -c
"$ADB" -s "$SERIAL" install -r "$APK" | tee "$EMULATOR_WORK/results/install.txt"
"$ADB" -s "$SERIAL" shell am force-stop io.gyrolab.x
"$ADB" -s "$SERIAL" shell am start -W -n io.gyrolab.x/io.gyrolab.app.MainActivity | tee "$EMULATOR_WORK/results/launch.txt"
sleep 3
"$ADB" -s "$SERIAL" shell uiautomator dump /sdcard/gyrolab-ui.xml > "$EMULATOR_WORK/results/dump.txt"
"$ADB" -s "$SERIAL" pull /sdcard/gyrolab-ui.xml "$EMULATOR_WORK/results/launch-ui.xml" >/dev/null
if ! rg -q 'package="io.gyrolab.x"' "$EMULATOR_WORK/results/launch-ui.xml"; then
  echo 'GyroLab was not present in the actual UI hierarchy; launch remains unverified.' >&2
  exit 6
fi
"$ADB" -s "$SERIAL" exec-out screencap -p > "$EMULATOR_WORK/results/launch.png"
"$ADB" -s "$SERIAL" logcat -d > "$EMULATOR_WORK/results/logcat.txt"
if rg -q 'FATAL EXCEPTION|Process: io.gyrolab.x.*PID:' "$EMULATOR_WORK/results/logcat.txt"; then
  echo 'Android crash found in logcat.' >&2
  exit 5
fi
SMOKE_PASSED=1
printf 'PASS: APK installed and launched on actual software-emulated Android image %s. Results: %s/results\n' "$IMAGE" "$EMULATOR_WORK"
printf 'Physical sensors, Bluetooth hosts and Windows pointer delivery remain unverified.\n'

# Keep the execution session alive as well as QEMU when more checks will follow.
if [ "${KEEP_EMULATOR:-0}" = 1 ] && [ -n "$EMU_PID" ]; then
  wait "$EMU_PID"
fi

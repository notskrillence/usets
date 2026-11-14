Current:0.1.2 host-initiated pairing; see PAIRING-0.1.2.md. Build/lint/signature passed;967 focused HID assertions passed. Hardware pairing unverified. Earlier results below are historical.

Latest update: version0.1.1 pairing fix; see PAIRING-FIX.md for current build/verification and retry instructions. Earlier0.1.0 evidence below is historical.

# Verification results

All automated checks below executed on Linux, using synthetic inputs or explicitly isolated Android doubles where stated. Full output: verification/TEST-LOG.txt.

| Suite | Passed |
|---|---:|
| PureJava motion numerical assertions |13,189|
| Recording/replay/sweep/target metrics (synthetic) |4,538|
| Native LabView control flow with Android doubles |24|
| Actual HidTransport source with Android API doubles |953|
| Actual UDP socket loopback, Android scheduler/clock doubles |31|
| Preset schema/configuration checks with test-only JSON library |62|
| Portable C receiver decode/order/stale/session/timeout checks |38|

Gradle :app:assembleDebug :app:lintDebug succeeded. Lint:0errors,36warnings (27text/localization,5touch-accessibility, highsampling, missingicon, drawallocation and dataextractionrules). The app has a generic system icon, private-use English UI, explicit high-rate permission and real accessibility click actions for touch pads. There is no lint baseline masking errors.

APK: v2 signature verification passed; minSDK28 target35; package io.gyrolab.x; version0.1.0. No native ABI dependency. The development key is included for source-rebuild upgrade continuity.

Review fixed compound-axis quaternion branch jumps, extreme gain-curve arithmetic, gravity-frame projection, API28 inset compatibility, overlapping calibration callbacks, zero-gain slider release, recording reset/bias/suppression context, lab idle/paused/stale timing and inaccessible selector popups. Tests support these mathematical/control-flow claims only.

Runtime: API35 x86_64 software emulator did not boot to ADB/framebuffer within300s. No app installation or UI result can be inferred from that attempt. Final APK SHA81136c… installed successfully and cold-launched as io.gyrolab.x/io.gyrolab.app.MainActivity on AndroidAPI30 Google ATD under software QEMU TCG. Earlier build SHA4e7e… passed two native-hierarchy assertions for the Mouse page and controls below the damaged upper quarter. The earlier layout evidence is not falsely attached to the final hash. The ATD framebuffer was black; no visual screenshot claim is made. Advanced/Lab runtime interaction and persistence automation timed out. Selected evidence: verification/android-runtime/. Final landscape/reverse-portrait physical-screen exclusion compiles; rotated runtime geometry still needs a device check.

Physical checks remain: install on OnePlus12R Android16; delivered sensor rates/noise/bias; orientation/grip usability; Bluetooth HID registration/pairing/reconnect; iPad and Windows mouse/buttons/scroll; real radio failure button behavior; Windows receiver execution; actual end-to-end latency and human target performance. No hardware benchmarks or universal game compatibility claims.

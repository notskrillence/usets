# Actual Android runtime evidence

The earlier signed APK (SHA2564e7e5dfb96feac2a724aee50ba7d55494f7e15a9c635b35a31ba376bd1bd4d6c) installed successfully on an actual Android API30 Google ATD x86_64 guest running under software QEMU TCG. This is Android execution, distinct from JVM test doubles. MainActivity ran; the captured native hierarchy contains the Mouse controls at y372 or below, with the first app status at y268 on a480x960 display. Two hierarchy assertions passed: native Mouse page present, and enabled interactive controls below the damaged upper quarter.

`install.txt`, `app-launch-log.txt`, and `mouse-ui.xml` are the selected evidence. The launcher initially reported the ATD fake-home component while the app started; the later Android log confirms `Displayed io.gyrolab.x/io.gyrolab.app.MainActivity`, and the hierarchy confirms its content. No GyroLab fatal stack was found in the retained first-run log. One early process was force-stopped by `installPackageLI` because a manual install overlapped the automated install; the log preserves that explicit cause. The later launch and hierarchy are the verified result.

The framebuffer was black under the ATD graphics setup, so no screenshot is offered as visual evidence. The software guest was slow and overloaded during first boot. An initialized guest restart and concise navigation attempt hit a25-second UI-dump timeout. Advanced/Lab navigation, persistence, virtual-gyro nonzero output and UDP were therefore not marked passed. Physical sensors, HID radio delivery, iPad and Windows pointer movement remain unverified.

## Final artifact reinstall
The final APK, SHA256 `81136c8983081fcf87bd2c32289c6145f983cc7eda9430b33d60b0a150d0b051`, was subsequently installed successfully on the same actual Android API30 guest. `final-install.txt` and `final-artifact-check.json` contain the separate result and hash. `final-launch.txt` records a successful COLD AM launch with Status ok and the exact `io.gyrolab.x/io.gyrolab.app.MainActivity` component. No final-build navigation or pixel-rendering claim is made. The old Mouse hierarchy remains associated with the earlier4e7e build, not this final8113 build.

The first final-install attempt was issued when the PackageManager service existed but the OS was not fully ready; StorageManager threw a null reference before app execution. That error is retained separately in `final-install-early-os-error.txt`. After `sys.boot_completed=1`, the retry succeeded.

## Reproduce
Install SDK packages emulator and `system-images;android-30;google_atd;x86_64`; set ANDROID_SDK_ROOT and JAVA_HOME, then run `KEEP_EMULATOR=1 EMULATOR_SPARSE_USERDATA=1 scripts/emulator-smoke.sh`. The optional sparse-data flag formats only a new task-owned userdata file using platform-tools/mke2fs, avoiding a6GiB physical reservation. The existing failed API35 image was removed to make room; SDK35 compile platform remains. Zero blocks in the API30 system image were made sparse with fallocate, without changing bytes.

The script keeps its execution session alive when KEEP_EMULATOR=1, allowing further adb checks; stop with adb emu kill when finished. Hardware verification is still separate.

# Latest build

Command: JAVA_HOME=/workspace/work/toolchain/jdk-21.0.13+9 GRADLE_USER_HOME=/workspace/work/toolchain/gradle-cache ./gradlew clean :app:assembleDebug :app:lintDebug --console=plain
Result: BUILD SUCCESSFUL in3s;43tasks executed. Android lint0errors,36warnings. Exact output is verification/BUILD-LOG.txt and LINT-REPORT.txt.
Tooling: Gradle8.13(checksummed wrapper), AGP8.9.1, AndroidSDK35/buildtools35.0.0, TemurinJDK21.0.13+9. No Android runtime-library dependencies; no native ABI library.
APK: app/build/outputs/apk/debug/app-debug.apk; signed v2 debug with included development key; io.gyrolab.x0.1.0, min28 target35. APK SHA256 81136c8983081fcf87bd2c32289c6145f983cc7eda9430b33d60b0a150d0b051.
Windows EXE: companion/build/GyroLabReceiver.exe; Linux-cross-compiled x64PE; SHA256 32ac5ee4b27b122a2d343b92b858d67d26fc23a6332dd6612352c12c06dc57ef.
First successful baseline preserved outside repository at ../milestones/gyrolab-baseline.apk.
Final staged build products: ../packaging/GyroLab-X-0.1.0.apk, GyroLab-X-source.zip, GyroLabReceiver-Windows-x64.zip, START-HERE.md, CHECKSUMS.sha256, ARTIFACTS.json. Source archive is produced from the final local Git commit after runtime result collection.
Output transfer blocked: required /codex/.../output is absent/unwritable in this cloud runtime; no generic outbound attachment tool. Do not invent download links. Retrieve staged packages through the cloud workspace file browser.
AndroidAPI30 emulator: actual final APK installation and cold launch passed (exact MainActivity). Earlier build Mouse hierarchy and lower-screen placement passed; the hashes are distinguished in verification/android-runtime/. Advanced/Lab UI and persistence remain unverified. Physical Android16/iPad/Windows behavior remains unverified.

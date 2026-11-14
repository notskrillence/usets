Latest update: version0.1.1 pairing fix; see PAIRING-FIX.md for current build/verification and retry instructions. Earlier0.1.0 evidence below is historical.

# Resume / recovery
Read REQUIREMENTS, PROGRESS, BUILD_STATUS, TEST_RESULTS, DECISIONS, RESEARCH and ACCEPTANCE. Native Android app/, independent math core/, replay lab/, optional Windows receiver companion/.
Toolchain ../toolchain: JDK21.0.13+9, Gradle8.13, Android SDK35/buildtools35.0.0. Set JAVA_HOME, ANDROID_SDK_ROOT and (in this proxy environment) GRADLE_USER_HOME=../toolchain/gradle-cache. User-local Gradle proxy/trust settings are outside the source archive. local.properties is untracked.
Build ./gradlew :app:assembleDebug :app:lintDebug. Verify ./scripts/test.sh with JDKbin on PATH and ANDROID_SDK_ROOT. Test totals18,835. Development signing key included; password/alias in development/README.
First baseline preserved ../milestones/. Current APK app/build/outputs/apk/debug/app-debug.apk. Final staged packages ../packaging/ once created. Preserve them before changing source. Never claim physical tests from JVM/doubles.
Runtime: API35 failed to boot, final APK installed and cold-launched on API30. Earlier Mouse hierarchy tests are labeled by their own hash. Selected evidence in verification/android-runtime; large task logs remain ../emulator2. Emulator lacked KVM and is stopped. Advanced/Lab/persistence and physical tests remain.
Delivery blocker: mandated /codex/browser/projectless/0001-files-pasted-by-the-user-uploaded-pasted-text-po/output cannot be created by task uid1000. There is no generic outbound upload or client-filesystem tool. Actual cloud build files may be retrieved via the user's cloud workspace file browser. Do not link nonexistent output paths.

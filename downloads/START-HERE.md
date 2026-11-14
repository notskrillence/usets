# GyroLab X 0.1.0

Install `GyroLab-X-0.1.0.apk` on the Android phone. The iPad/Windows computer is the mouse host; no app is installed on the iPad.

1. Open GyroLab X, tap **Pair / discover**, and grant Nearby devices.
2. From iPad Settings → Bluetooth (or Windows Bluetooth settings), pair with the phone. Confirm on both devices.
3. Return to GyroLab X → **Connect host** → select the paired host.
4. Hold still and **Calibrate 2s**, then **ENABLE tracking**. Rotate the phone to move. Adjust sensitivity, use click/drag/wheel pads, and **Recenter** as needed.

Keep the app visible. It pauses/releases input when interrupted. Controls reserve the damaged upper28% of the screen; scroll within the lower area. Host pointer settings affect the counts-to-pixels conversion.

`GyroLab-X-source.zip` contains the complete source, Gradle wrapper, development signing key, standalone software tests, algorithm and lab descriptions, original-source findings/licenses, verification logs and build instructions. Run `./gradlew :app:assembleDebug :app:lintDebug` with JDK17/21 and Android SDK35.

`GyroLabReceiver-Windows-x64.zip` is optional Wi-Fi output for Windows. Bluetooth HID remains the default and the iPad path. The Windows executable was cross-compiled; actual Windows execution is unverified.

Build, lint, signature and18,835 software assertions/checks passed. The final APK installed and cold-launched on AndroidAPI30 software emulation; earlier Mouse hierarchy checks are separately labeled. Software tests use synthetic sequences, Android API doubles or portable protocol/loopback checks. Physical OnePlus12R Android16 sensor behavior, Bluetooth pairing and real iPad/Windows mouse movement remain unverified. Experimental modes and replay proxy tuning do not establish improved human accuracy.

Check `CHECKSUMS.sha256` and `ARTIFACTS.json` for integrity/provenance. Read README, ACCEPTANCE and TEST_RESULTS inside the source archive for the exact scope and limits.

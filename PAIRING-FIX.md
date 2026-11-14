# 0.1.1 pairing fix

Reported symptom: both numeric pairing codes match, then pairing stalls and reports incorrect PIN. This does not establish that the numeric PIN itself is wrong.

Changes: keep Bluetooth HID registration foreground via a connected-device foreground service while system pairing dialogs take focus; wait for the HID registration callback and active service before requesting discoverability. Tracking still pauses and releases buttons when the Activity is hidden. VersionCode2 uses the same development signing key, allowing an upgrade over0.1.0.

Verification: APK compilation, Android lint and APK signature check passed;953 focused production HID/API-double assertions passed. Physical pairing fix remains unverified. No lengthy emulator tests were repeated.

Install0.1.1 over the previous version. On both phone and iPad/PC, forget the failed old pairing first. Reopen GyroLab, tap Pair / discover, allow discoverability, then initiate pairing from iPad/PC and confirm both codes. Keep GyroLab open while confirming. If the attempt still fails, the next useful evidence is the app's Bluetooth status immediately after confirmation.

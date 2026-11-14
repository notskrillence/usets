Latest update: version0.1.1 pairing fix; see PAIRING-FIX.md for current build/verification and retry instructions. Earlier0.1.0 evidence below is historical.

# Progress
Complete: pinned MIT source investigation; user-local JDK/SDK/Gradle; preserved first compiled signed baseline; integrated APK with primary Bluetooth HID for Windows/iPad,12 motion modes, calibration/clutch/buttons/wheel, lower-screen UI, presets, real local tests, recording/replay/comparison, explicit proxy tuning, diagnostics; optional Windows x64 UDP receiver.
Verified: Gradle APK build + lint (0errors,36warnings);18,835 software assertions/checks across core, lab, control flow, HID doubles, UDP loopback/doubles, strict presets and C protocol. Signature verified. See verification/ logs.
Complete: final signed APK installed and cold-launched on actual AndroidAPI30 software emulator; earlier build native Mouse hierarchy and lower-screen placement checked. See verification/android-runtime for precise artifact hashes. Final packages staged in ../packaging. API35 software boot timed out; no physical tests claimed.
Blocked: this cloud task cannot create /codex/.../output (Unix Permission denied); no outbound attachment/transfer tool exists. Preserve staged packages under work pending a supported output transfer. Do not fake download links.
Unverified: physical OnePlus Android16 sensor behavior; Bluetooth pairing and real iPad/Windows input; Windows EXE execution and performance.
Deferred: wiredADB, host closed loop/precisionGUI, blind randomized human trials, human-performance autooptimization, delay experiments, wrist-range autocalibration, long-term adaptation.

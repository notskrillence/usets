# Source reconnaissance and implementation evidence

Inspected 2026-11-14. Reference checkouts are development inputs, not runtime dependencies. No physical Android or Windows measurements were possible in this Linux environment. Source claims below describe the pinned upstream source, not validated hardware behavior.

## Pinned source revisions

| Repository | Commit inspected | License |
|---|---|---|
| [GhostBoard](https://github.com/ToxicOrca/ghostboard-android-bluetooth-mouse-keyboard) | `135a3f7333553afe45467b04da514430c7383e0c` | MIT, copyright 2026 |
| [Mousedroid](https://github.com/darusc/Mousedroid) | `3d077786c4ab333f648fc3c2b2ad668203af09b2` | MIT, copyright 2025 darusc |
| [AirCursor](https://github.com/m-afham/AirCursor) | `b1ac85182a27b1d766ac487df22a6a908a4188f7` | MIT, copyright 2026 m-afham and AirCursor contributors |

The source archives can be reconstructed with Git at these SHAs. Their licenses permit adaptation with notices. This project uses a lean independent Java motion/transport implementation instead of accumulating the upstream Android UI/Kotlin, macOS Swift, or ASIO dependencies. The transport conventions are standard HID/Windows APIs. Attribution is preserved in `THIRD_PARTY_NOTICES.md`.

## GhostBoard: validated structure, unmeasured latency

Examined `app/src/main/java/com/example/remoteinput/bluetooth/BluetoothHidManager.kt`, `MainActivity.kt`, and `app/src/main/AndroidManifest.xml`.

* `BluetoothAdapter.getProfileProxy(..., HID_DEVICE)` obtains `BluetoothHidDevice`; registration uses `BluetoothHidDeviceAppSdpSettings` and `onAppStatusChanged` tracks registration. A pending device and saved Bluetooth address support reconnect. Pairing/discoverability is delegated to Android intents and Windows pairing. Its failed-connect-device set avoids repeated failed connect toasts and suggests connecting from Windows.
* Combined keyboard/mouse SDP uses report IDs 1 and 2. Mouse report ID 2 has four bytes: button mask (left=1, right=2, middle=4), signed X, signed Y, signed wheel. The logical displacement bounds are -127 and +127; the five unused button bits are padding. `sendReport` takes the report ID separately, so the four-byte payload excludes that ID.
* It maintains `currentButtons` while moving and scrolling. Its getter report callback returns an empty four-byte mouse report. A dedicated handler thread coalesces motion at an 8 ms requested interval. QoS requests best effort with tokenRate 800, tokenBucketSize 9, peakBandwidth 0, latency 11250, delayVariation MAX. These request values do not establish delivered Bluetooth polling rate or latency.
* The final `clampByte` loses any coalesced motion beyond 127 counts, and click dispatch sleeps 50 ms on the send thread. These are concrete reasons to improve report chunking and asynchronous scheduling instead of transplanting the class unchanged. Idle reports after two seconds are an upstream heuristic, not a measured OnePlus result.
* Runtime CONNECT, ADVERTISE and SCAN permissions are requested in the Activity; legacy permissions are also declared. The source does not establish Android 16/OxygenOS compatibility or a foreground-service policy.

Decision: use a mouse-only HID descriptor with explicit signed bounds; preserve overflow/fractional motion, keep button-state transitions ordered, and observe callback state. Do not inherit clamp loss, blocking click sleep, or unverified sniff-mode latency claims.

## Mousedroid: three real transport paths

Examined `client/.../networking/ConnectionManager.kt`, `Translators.kt`, `Connection.kt`, `sockets/{UDPConnection,TCPConnection}.kt`, `bluetooth/BluetoothConnection.kt`, `mkinput/GestureHandler.kt`; desktop `server/src/net/{server,connection}.cpp`, `server/src/adb.hpp`, `server/src/input/win32/windowsinputmanager.cpp`.

* Its current Bluetooth path is **direct Android Bluetooth HID**, not RFCOMM/serial Bluetooth. It calls `registerApp` with keyboard, media, battery and mouse descriptors. It uses an unlimited coroutine report channel; this is not a bounded low-latency strategy to copy. QoS exists as a field but the actual registration passes null QoS.
* `ConnectionManager.connection` prioritizes UDP over TCP over Bluetooth. Wi-Fi creates TCP for connection setup/device details and UDP for input; Android UDP serialization uses small event opcodes (1=left click, 2=right click, 3=down, 4=up, 5=move, 6=scroll, etc.) and signed byte movement. It does not include timestamp, sequence number, session identifier, acknowledgement or stale-packet handling.
* `UDPConnection.send` sleeps 15 ms after each report and swallows send exceptions. This implementation is unsuitable as proof of low latency or delivery. Movement `toByte` conversion can overflow when input is not bounded; some gesture paths pre-clamp.
* USB mode uses Android TCP to `127.0.0.1`, with desktop `adb reverse tcp:<port> tcp:<port>` routing Android loopback to the PC TCP server. It is ADB-over-USB, not native USB HID. ADB does not forward UDP; a separate TCP transport is necessary.
* Windows implementation uses relative `SendInput(INPUT_MOUSE)` for movement, explicit button flags, and wheel input. It applies its own sensitivity multiplier. Mouse counts are not guaranteed screen pixels. Some click paths combine DOWN and UP in one INPUT structure; explicit ordered press/release events are easier to verify.
* Gesture handler implements taps, double taps, two-finger scroll, zoom, and drag, with intentional delays to disambiguate gestures. Those touchpad decisions are not gyro latency requirements.

Decision: implement a small versioned sequence-numbered UDP protocol and Windows `SendInput` receiver, plus TCP framing for ADB reverse. Include full button state/heartbeats and idle release. Treat receiver acknowledgement as observed delivery only; it cannot measure full sensor-to-photon latency.

## AirCursor: transferable motion ideas

Examined `Sources/AirCursorCore/{Motion,Pointer,Math,Gestures}.swift`, `Sources/AirCursor/{AppController,DebugSnapshot}.swift`, `Services/{Diagnostics,HeadphoneMotionService,EventSynthesizer}.swift`, and core tests.

* It receives AirPods attitude and differentiates shortest wrapped yaw/pitch/roll differences over true elapsed time. `MotionDifferentiator` rejects dt <2 ms and >250 ms, preventing wrap and long-gap jumps. Android already supplies gyro angular velocity, so differentiating an orientation signal again is unnecessary for the relative baseline.
* `PointerMapper` uses 28 screen points per degree at sensitivity one, a subtracted dead zone, bounded linear acceleration, and separate inversions. These are project defaults, not Android-calibrated gains.
* `PointerSmoother` uses `alpha=1-pow(retention, dt*60)` and integrates velocity by dt; target readings older than 120 ms decay toward zero. Its rendering loop runs at 120 Hz. The useful principle is elapsed-time filtering/stale-input handling; Android should keep the sensor/report pipeline independent of display refresh.
* It keeps cursor history and, for gesture clicks, chooses the position 60 ms before gesture onset. This compensation needs actual host coordinates; blindly sending relative inverse motion on Android could introduce errors under Windows acceleration. Large touch pads and optional click suppression are simpler initial controls.
* Diagnostics distinguish missing motion samples, Bluetooth/audio handoff, motion authorization, accessibility authorization, and pointer generation. Recenter/reset does not imply drift elimination.

Decision: preserve frame-independent filter math, explicit discontinuity resets, click perturbation awareness and stage-specific diagnostics. Do not transplant macOS coordinate or CoreMotion assumptions.

## API and mathematical references consulted

All pages below were fetched successfully during implementation; no source-reported benchmark is treated as a local measurement.

* [Android sensor overview](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview): Android 12+ motion sampling is rate limited without `HIGH_SAMPLING_RATE_SENSORS`; microphone privacy controls can impose rate limits regardless. Continuous sensors do not supply background app events on Android 9+. Foreground Activity operation is a defensible baseline; sensors are unregistered and output released on pause.
* [SensorManager](https://developer.android.com/reference/android/hardware/SensorManager): `registerListener(listener,sensor,periodUs,0,handler)` requests earliest delivery rather than FIFO batching. Requested sampling period is not a delivery guarantee. Sensor FIFO capacity, minDelay, maxRange and resolution should be inspected at runtime.
* [SensorEvent](https://developer.android.com/reference/android/hardware/SensorEvent), [motion sensors](https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion): gyro units rad/s; nanosecond sample timestamps use the `elapsedRealtimeNanos()` time base. Integrate `omega*dt`; distinguish timestamps from callback dispatch time. Accelerometer includes gravity/linear acceleration; reject implausible acceleration when correcting attitude. Uncalibrated gyro reports vendor bias estimates in values[3..5]; game rotation vector avoids magnetometer but cannot establish absolute yaw.
* [BluetoothHidDevice](https://developer.android.com/reference/android/bluetooth/BluetoothHidDevice): API 28+, only one app registration at a time; callback confirms registration, not the boolean return alone. Apps are automatically unregistered when not foreground. CONNECT permission is required for modern targets. There is no API guarantee of an 8 ms link interval.
* [One Euro filter authors](https://gery.casiez.net/1euro/) and their [reference Python implementation](https://github.com/casiez/OneEuroFilter/blob/main/python/OneEuroFilter/OneEuroFilter.py): derivative low-pass; cutoff `minCutoff + beta*abs(filteredDerivative)`; low-pass alpha `dt/(dt+1/(2*pi*cutoff))`. Units affect beta. Tune stability first, then beta for fast motion. Applying this to velocity is a stated design choice, not a claim of identical behavior to filtering position.
* [x-io Fusion documentation](https://github.com/xioTechnologies/Fusion): complementary attitude estimation integrates gyro with gravity/heading error feedback. Gain trades gyro drift against acceleration/magnetic error; invalid acceleration should be rejected. Gyro+accelerometer fusion cannot correct unobservable yaw. Complementary/quaternion fusion is sufficient for an experimental mode; Madgwick, Mahony and Kalman names should not be advertised without actual corresponding implementations.
* [Steam Input source modes](https://partner.steamgames.com/doc/features/steam_controller/input_source_modes): relative mouse, mouse region and sensitivity/acceleration/dead-zone options describe distinct control mappings. Small movement accumulation is useful; nominal settings do not establish accuracy superiority.
* [Win32 SendInput](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-sendinput): check inserted event count; UIPI limits injection to equal/lower integrity applications. No game or anti-cheat compatibility guarantee. [MOUSEINPUT](https://learn.microsoft.com/en-us/windows/win32/api/winuser/ns-winuser-mouseinput) explicitly states relative motion is affected by Windows mouse speed/threshold settings.
* [Windows Raw Input](https://learn.microsoft.com/en-us/windows/win32/inputdev/about-raw-input): device input arrives through WM_INPUT and device registration; cursor-position sampling/SendInput acknowledgement is not equivalent to measuring accepted Raw Input in an application. Keep normal mouse/touchpad coexistence.
* [MacKenzie, Sellen & Buxton, CHI 1991](https://www.yorku.ca/mack/CHI91.html): Fitts comparisons require controlled amplitude/width and movement time; error distributions and task conditions matter. Report hit/miss, path efficiency, time and endpoint distributions directly. Do not label `log2(D/W+1)/time` from a few mixed trials as effective throughput. Learning/adaptation requires new held-out target sequences and a reversible chosen setting.

## Research boundaries

No wrist control algorithm is empirically superior from these readings alone. Noise attenuation on a stationary/synthetic trace can conflict with deliberate fine correction. Tremor-notch, predictive and dual-path modes remain experiments: compare response/phase lag, reversal behavior and real target acquisition rather than fabricating human-control results. Temperature drift is not measurable without sensor support. True end-to-end latency needs phone/PC hardware and clock/observation design. Hardware verification remains a separate acceptance gate.

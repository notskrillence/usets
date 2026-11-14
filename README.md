# GyroLab X

Download the [Android APK](downloads/GyroLab-X-0.1.0.apk?raw=true), [source archive](downloads/GyroLab-X-source.zip?raw=true), or [optional Windows receiver](downloads/GyroLabReceiver-Windows-x64.zip?raw=true). Read [START-HERE](downloads/START-HERE.md) for installation and pairing. Bluetooth HID is the default iPad/Windows path; physical pairing remains unverified.

An Android gyroscopic mouse with direct Bluetooth HID output, twelve deterministic motion processors, and a local measurement/replay laboratory. The APK runs on the phone; an iPad or Windows computer is the Bluetooth mouse host. No receiver is needed for Bluetooth.

## Start with an iPad or Windows PC

1. Install the debug APK on the Android phone and open **GyroLab X**. Android may ask to allow APK installation from your file browser.
2. Tap **Pair / discover** and grant **Nearby devices**. Turn Bluetooth on if prompted, then tap Pair again.
3. On the iPad, open **Settings → Bluetooth**, select the phone's Bluetooth name (or GyroLab X), and confirm pairing on both devices. On Windows use **Settings → Bluetooth & devices → Add device**.
4. Return to GyroLab X and tap **Connect host**, then select the paired iPad/PC. Wait for the connection status. If the host initiates the HID connection itself, no second connection is needed.
5. Hold the phone comfortably, tap **Calibrate 2s**, and keep it still. Then tap **ENABLE tracking** and rotate the phone. Adjust sensitivity. **Recenter** resets the reference without moving the host pointer.

Physical Android/iPad/Windows pairing and pointer movement have not been verified in this build environment. The standard Bluetooth HID descriptor is implemented and software-tested; OxygenOS profile support and host behavior still need a real device check. Another registered phone HID app may prevent registration. If pairing fails, close other phone mouse/keyboard apps, remove the pairing on both devices, and retry while GyroLab X is visible. Modern iPadOS supports Bluetooth mice; host-specific pointer settings can change the feel.

## Everyday controls

Portrait pages reserve the upper28% of the screen and put controls in the lower area. Landscape pages use a central safe column to avoid the physically damaged side after rotation; reverse portrait also reserves the bottom damaged region. Scroll to reach additional controls; dialogs also open at the bottom. Android's own permission/pairing/file-picker screens are controlled by the OS.

- **LEFT / drag**, **RIGHT**, **MIDDLE** are press-and-hold pads. Release lifts the button. **Drag lock** toggles left hold; wheel arrows send real scroll detents.
- **PAUSE**, recenter, mode/settings changes, disconnect, and leaving the Activity release held buttons and clear fractional motion. A radio failure cannot guarantee delivery of a final release; the code also clears button state on reconnect.
- The clutch defaults to hold-to-pause. Advanced can change it to hold-to-move. Volume-up/down can act as left/right buttons while the app is foreground.
- Touch-click movement suppression is45ms by default. Disable it in Advanced if you prefer immediate motion.
- Keep the app visible and the screen on. It intentionally pauses on Activity interruption; background operation is not implemented. Reenable tracking when you return. An OEM HID unregister may require Connect again.

The default axes are device Y→horizontal and device X→vertical. Advanced offers axis selection, inversion, independent gains and grip roll. **Gravity-aligned recentered axes** aligns horizontal yaw to measured gravity for flat/arbitrary grips. Recenter after changing grip. This is not an absolute compass; yaw drift remains possible. Portrait/landscape layout changes reset tracking; axis mapping remains explicit in Advanced.

Sensitivity is mouse **counts/radian**, not guaranteed pixels. Counts/degree = counts/radian×π/180. iPad pointer settings and Windows speed/acceleration/display settings affect the final mapping.

## Modes and presets

Mouse switches instantly between direct angular, fixed low-pass, One Euro, orientation anchored, nonlinear precision, quaternion gravity fusion, relative/anchored hybrid, tremor notch, bounded prediction, ballistic/fine, composed pipeline, and confidence dual path. Advanced edits each real parameter. The composer has a fixed valid order: bias/axes → optional soft deadzone → low-pass → nonlinear gain → bounded prediction. See [core/README.md](core/README.md) for equations and limits.

Defaults favor the direct baseline. Experimental modes are hypotheses rather than established improvements. Anchored projection is clipped to a comfortable range and reanchors pathological quaternion-log branch changes. Gravity fusion can introduce acceleration corrections and cannot remove yaw drift. Prediction can overshoot. The notch can remove useful intentional motion as well as oscillation.

Advanced provides local named presets, overwrite/edit, delete, JSON import/export, last-settings persistence, manual A/B swap and undo. Save under a new name to duplicate. Apply changes explicitly; nothing tunes silently during a trial.

## Local accuracy and replay lab

The native Lab uses the same floating-point engine output as transport generation, in counts. It tests the phone/local algorithm; it does **not** measure Bluetooth delivery or host pointer performance. UI cursor traces are sampled at approximately30Hz display cadence; rapid sub-frame paths may be coalesced. Target geometry is deterministic for repeatable runs.

Choose static targets, micro-adjustments, horizontal, vertical,15s moving pursuit, or10s stationary drift. Enable tracking, rotate to aim, and use **HIT** for target acquisition. Touching the canvas cannot aim or hit. Targets begin timing with real input; stale input over250ms or interruption produces a partial result. Use task restart/recenter for a fresh trial. Exports contain actual local attempts, acquisition time, endpoint error, path efficiency and traces; timed runs report RMS/tracking or unbounded generated-count drift. Nominal Fitts metrics are labeled; effective human throughput is not claimed.

**Record motion** requires engaged tracking, resets the processor, and captures up to60,000 actual timestamped gyro/accel samples with initial configuration and bias. It is a separate raw-motion capture and interrupts an active accuracy trial. Touch-click suppression is disabled during capture. Pause, clutch/reference/mode changes, calibration or app interruption stop the segment so replay does not silently miss a state reset. Export before replacing a recording. Recordings are held in memory until exported; they do not survive process termination.

Replay comparison starts every processor from the recorded initial state and feeds exactly the same samples. It compares generated paths/net displacement/RMS/trajectory difference and measured computation time. The plotted current-mode trajectory is auto-scaled counts. Computation timing is device/JVM processing cost, not sensor-to-host latency. Counterfactual replay cannot predict human hit rates under another feedback function.

The One Euro parameter sweep selects among20 minimum-cutoff/beta pairs using the first two thirds, then reports untouched final-third proxy scores. The Android button selects the **stability** proxy. Use stationary captures; reduced movement can also slow aiming. Applying a candidate is explicit and undoable. It is not automatic optimization of human speed/accuracy. The pure-Java API also exposes tracking/balanced proxies. See [lab/README.md](lab/README.md).

Import `examples/synthetic-stationary-sweep.csv` to exercise replay without hardware motion. It is explicitly labeled **synthetic_example** and supplies no hardware or human evidence.

## Diagnostics and optional receiver

Advanced/Lab displays actual gyro rate, interval percentiles/jitter, bad timestamps/gaps, dispatch delay, processor time, stationarity, orientation, API report submissions/rejections/disconnections, fractional backlog and record count. Delivered sensor rate may differ from the requested100/200/400Hz. The high-sampling-rate permission is declared; Android/OEM restrictions may still cap it. Data older than100ms is discarded with a reference reset to prevent stale movement bursts. API submission counts do not prove host delivery.

An optional portable Windows x64 receiver is in `companion/`. It uses UDP26761 and SendInput; enter the PC's local IPv4 address from Advanced. Phone status deliberately says **host unconfirmed** because there is no ACK. See [companion/README.md](companion/README.md). Bluetooth remains the default and is the iPad path. Windows receiver execution is unverified; it is cross-compiled with passing portable protocol tests. USB/ADB, host closed-loop correction, a Windows precision GUI, blind randomized human comparisons, artificial output delay, automatic wrist-range calibration and long-term adaptation are not implemented.

## Build and test

Install JDK17 or21 and Android SDK platform35/build-tools35.0.0. Point `ANDROID_HOME`/`ANDROID_SDK_ROOT` to your SDK or set `sdk.dir` in an untracked `local.properties` file. Then:

```sh
./gradlew :app:assembleDebug :app:lintDebug
ANDROID_SDK_ROOT=/path/to/android-sdk ./scripts/test.sh
```

On Windows use `gradlew.bat` for the APK. Standalone scripts need a POSIX shell and a C compiler. Gradle8.13 wrapper distribution is pinned by SHA256. AGP8.9.1 is pinned. The Android app has no third-party runtime library dependencies. A development-only signing key is included so rebuilt APKs can upgrade this delivered build; details in `development/README.md`.

APK output: `app/build/outputs/apk/debug/app-debug.apk`. No native ABI library is used, so the same APK supports ARM64. Minimum Android API28, compile/target35; Android16 device behavior still requires hardware verification. Tests use generated input, isolated Android doubles, real local UDP sockets, or portable C; none substitute for hardware tests. See `TEST_RESULTS.md` and `BUILD_STATUS.md` for exact verified outcomes, and `RESEARCH.md` for pinned source investigation and evidence.

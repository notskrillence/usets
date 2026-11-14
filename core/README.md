# Motion core

Pure Java 8, independent of Android, transports, and the UI clock. `MotionEngine.process` takes a monotonic nanosecond timestamp, gyroscope radians/second, and accelerometer m/s². Outputs `dx`, `dy` are floating-point **mouse counts**, not guaranteed Windows pixels. Each algorithm gets actual sample time. Defaults use device Y for horizontal and X for vertical; inversion, independent counts/radian, and grip roll are configurable.

The first sample, non-increasing timestamps, invalid gyro samples, and >100 ms gaps emit zero. Gaps reanchor and clear filters. Calling `reset()` reanchors without movement. The integration owner must also clear its quantizer, delayed reports, and held buttons when pausing/disconnecting. Duplicate timestamps never advance time.

## Frames and calibration

Quaternion orientation integrates calibrated device gyro using the exponential rotation increment, normalized each sample. `gravityRelative` rotates gyro into the initial reference frame, then projects into a basis with Y = normalized initial measured gravity, X = reference device X projected perpendicular to gravity (fallback device Z), and Z = X×Y. Thus default horizontal is rotation about gravity for a flat or upright initial grip. Valid accelerometer data is needed to establish that basis. There is no magnetometer or independent yaw reference; yaw drift remains possible.

A stationary `Calibration` uses Welford bias and variance statistics, actual interval mean/jitter/percentiles, and rejects high-noise, short, or implausibly high mean-bias (≥0.15 rad/s) samples. Constant very slow intentional rotation is fundamentally ambiguous with bias, so stationary calibration still requires physically holding the phone still. Optional automatic bias updates are disabled by default: deliberate slow motion can resemble stationary bias, so enable only during controlled testing.

## Twelve modes

| Mode | Mathematical behavior / tradeoff |
| --- | --- |
| Direct angular | Corrected rate × actual dt × gain. Baseline with no output smoothing. |
| Fixed low-pass | Velocity exponential filter, α = 1 − exp(−2π f dt), then integration. Adds lag. |
| One Euro | Filter the velocity derivative, increase velocity cutoff by β × absolute filtered derivative. Adaptive lag/noise tradeoff. |
| Orientation anchored | Unwrapped components of the quaternion rotation vector mapped through a clipped tangent projection; output changes in desired counts. Range ±1.05 rad. |
| Nonlinear precision | Smooth speed gain `precision + (1−precision) u/(1+u)`, `u=(speed/scale)^power`. Reduced fine movement gain. |
| Quaternion gravity fusion | Accelerometer-confidence-weighted complementary gyro correction from measured × predicted gravity. The local quaternion tangent increment (corrected angular rate × dt) maps to counts, avoiding a global quaternion-log branch. No yaw correction. |
| Relative / anchored hybrid | Explicit blend of direct angular increments and anchored projected increments. |
| Tremor notch | Second-order IIR notch, configurable frequency and Q, recalculated for sample dt. Reduces a selected oscillation frequency; irregular sampling changes the notch response. |
| Bounded prediction | Angular acceleration × bounded 0–40 ms horizon, bounded to one current-rate magnitude. Suppressed at reversals and stationary confidence >0.8. Can overshoot. |
| Ballistic / fine | Hysteretic motion-phase state with 35 ms transition; fine gain below threshold and direct gain during sweeps. |
| Composed pipeline | Ordered radial deadzone → optional velocity low-pass → nonlinear gain → bounded prediction. Blocks and parameters are explicit; filtering cannot run twice in this supported order. |
| Confidence dual path | Precision low-pass path and direct fast path; speed, onset, and reversal determine bypass. Stationary confidence attenuates only the precision path so onset is not suppressed by stale stationary confidence. Experimental. |

Orientation-anchored and hybrid modes use quaternion rotation-vector component projections, rather than globally exact screen rays. They avoid Euler gimbal lock, but simultaneous large multi-axis turns can distort that projection. The bounded comfortable-wrist range is intentional. Pure-axis and compound-axis wraparound are tested. A projected log increment larger than four times the measured angular step reanchors that local projection without emitting the discontinuity; `projectionReanchors` counts such events. This loses that projection sample at pathological poses rather than creating a large cursor jump. A globally exact orientation-to-screen mapping for unrestricted rotations is not claimed. Fusion can introduce correction artifacts from linear acceleration; accelerometer confidence gates large magnitude deviations but does not identify every acceleration.

## Experimental dual-path hypothesis

Hypothesis: separating stationary precision from fast onset response can attenuate small holding oscillations without delaying strong movement onset. A synthetic 10 Hz, 0.003 rad/s sequence checks output energy falls below 5% of direct angular; a following 0.5 rad/s onset checks at least 99.8% direct response. Those numerical properties pass. They do **not** establish improved human target acquisition or superiority to One Euro; controlled user trials remain necessary.

## Quantization

`Quantizer.add` preserves fractional counts, `drain` removes at most ±127 per axis, and excess displacement remains for subsequent reports. No signed overflow or saturation truncation. Inputs and accumulator overflow are checked. Transport code must impose an explicit age/backlog policy and report drops rather than allow stale movement to accumulate.

## Tests

`CoreTests` is a standalone synthetic JVM suite without dependencies. Compile main/test files and run `io.gyrolab.core.CoreTests`; latest execution passed 13,189 numerical assertions. Checks cover units, sample-rate and irregular-dt integration, axis gain/inversion/grip and gravity frames, normalized quaternions, pure-axis wraparound, stale/duplicate timestamps, mode reset, fractional/signed report encoding boundaries, calibration statistics, exact low-pass response, One Euro response, notch attenuation, bounded prediction/reversal, distinct deterministic mode signatures, composition behavior, deterministic replay, and the limited dual-path hypothesis above.

These are mathematical software tests. Android sensor callbacks, Bluetooth HID, touchscreen behavior, Windows pointer movement, physical tremor suppression, and device latency are not verified by them.

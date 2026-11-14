# Decisions
- Native Java Activity, no third-party Android runtime libraries; min API 28 (BluetoothHidDevice), target/compile 35 supports Android 16 devices.
- Primary Bluetooth HID standard 3-button signed8 relative mouse, direct iPad/Windows pairing. Companion optional.
- Dedicated HandlerThread sensor/output work; UI telemetry throttled; foreground Activity operation, pause/release on interruption.
- Actual sensor timestamp integration, report fractional carry, bounded backlog with explicit failure rather than hidden stale movement.
- Do not claim physical host success from compiling or passing simulations.
- Recording starts from captured live-engine bias and a reset state; engagement required. Every input-state/reference/config change stops the segment; touch-click suppression is disabled in capture. This preserves deterministic raw algorithm replay without pretending to reproduce host/OS transformations.
- No hardware confidence inferred from sensor simulations or Android doubles. Local lab trial clocks arm on first live core update; >250ms missing input interrupts. Backlog/old sensor data are explicitly dropped/reset instead of replayed late.
- Staged final packages remain in work because the mandated client output directory is not writable from the cloud runtime; no fabricated downloads.

- SafeArea conservatively reserves both landscape sides and reverse-portrait bottom because the damaged natural top rotates with the physical phone. Final portrait APK installed/cold-launched on AndroidAPI30; older Mouse hierarchy evidence is labeled separately.

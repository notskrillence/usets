# 0.1.2: host-initiated Bluetooth pairing

Pair now cancels retries/old outgoing connection state, unregisters/re-registers the HID mouse, waits for callback-confirmed readiness, then requests five minutes of discoverability. The iPad/PC initiates pairing and HID connection. Outgoing HID connect is blocked until Android reports BOND_BONDED; registration no longer initiates a connection merely because a plugged-device callback supplied an address. The connected-device foreground service stays active through system dialogs. Pointer tracking still pauses when hidden.

No custom PIN, passkey handler, authentication layer, or forced0000 PIN exists. Android/iPad generate and verify Bluetooth confirmation codes; an ordinary unrooted HID app cannot disable that OS-level negotiation. A matching-code timeout does not establish that the user entered a wrong PIN.

Install0.1.2 over the older app. Forget the failed pairing on both devices once, then reopen GyroLab and tap Pair / discover. Select the phone from iPad/PC Bluetooth settings and confirm the system prompts. Let the host connect automatically. Connect host remains available for an already bonded device if the host does not connect itself.

If unsuccessful, Advanced → Copy Bluetooth status captures the OS bond state/reason and HID stage. These values distinguish bonding failure from HID registration/connection failure. A numeric OS reason is reported without inventing a translation.

Verification: APK build, Android lint and signature passed.967 focused assertions against actual HID code and Android API doubles passed, including no outgoing connection during BOND_BONDING and no premature discovery readiness. Physical Android/iPad pairing remains unverified. No lengthy emulator tests repeated.

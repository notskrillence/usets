# GyroLab X optional Windows receiver

`build/GyroLabReceiver.exe` is a compiled portable x64 Windows console application. Bluetooth HID is the app's primary output; this receiver supplies the optional Wi-Fi path. It needs no .NET, Python, account, or installer. Windows 11 includes its required system DLLs.

1. Run `GyroLabReceiver.exe` on the Windows PC.
2. If Windows Firewall asks, allow the program on your trusted Private network.
3. On the phone, select UDP and enter the PC's local IPv4 address. Default port: `26761`.
4. Connect and enable tracking. Ctrl+C closes the receiver and releases its held mouse buttons.

Normal mice can be used concurrently. SendInput follows Windows pointer settings and can be rejected by elevated applications, protected desktops, or games. This receiver does not bypass those restrictions. For repeatable trials, document Windows pointer speed, enhanced pointer precision, display scaling, and resolution.

Run `GyroLabReceiver.exe --log reports.csv` for actual receiver submission records and sampled host cursor positions. Columns include receiver monotonic milliseconds, sender monotonic nanoseconds, session, sequence, displacement, buttons, wheel, cursor position, and SendInput success. Sender and receiver clocks are unrelated: subtracting them does **not** measure end-to-end latency. GetCursorPos is sampled immediately after submission and is not proof that every application consumed the input or a closed-loop correction mechanism.

The console reports accepted/rejected packets and SendInput failures. No ACK or host feedback is sent to the phone; the phone's UDP status can show socket submission only, not verified Windows delivery. Check the receiver console to confirm arrivals.

## Protocol

One UDP datagram = exactly 40 bytes, big endian:

| Offset | Type | Value |
|---|---|---|
| 0 | 4 bytes | ASCII `GLX1` |
| 4 | uint32 | session identifier |
| 8 | uint32 | increasing sequence, wrap supported |
| 12 | uint64 | sender packet-creation monotonic timestamp, ns |
| 20 | int32 | relative X counts |
| 24 | int32 | relative Y counts |
| 28 | int32 | full button state: left=1, right=2, middle=4 |
| 32 | int32 | wheel detents, positive=up |
| 36 | uint32 | flags: 1=heartbeat, 2=reset/release |

Send a zero-motion, zero-buttons reset when opening a new session. All reports carry the complete button state. Send a heartbeat at least every 100 ms while connected, including while paused. A reset packet must contain zero movement, buttons, and wheel. A heartbeat may carry the current button state. Movement ranges are bounded to ±32767 counts and wheel ±127 detents per packet; clients should normally emit much smaller increments.

The receiver rejects malformed datagrams, duplicate/out-of-order sequences, recently retired sessions, packets from another IP while an active stream exists, and packets more than 250 ms behind the observed relative-clock baseline. That stale check cannot detect delay before the first packet. It releases held buttons after 500 ms without an accepted report. Lost UDP movement is not retransmitted, avoiding delayed movement bursts. Button states are repaired by the next accepted full-state report or heartbeat; timeout releases them if the stream stops. This path has no authentication and is intended for a trusted local network.

## Build and verification

On Windows with MinGW GCC:

```bat
gcc -std=c11 -Wall -Wextra -Werror -O2 receiver.c -o GyroLabReceiver.exe -lws2_32 -luser32
```

On Linux with a MinGW cross compiler:

```sh
MINGW_CC=x86_64-w64-mingw32-gcc ./build.sh
```

The delivered binary was cross-compiled on Linux using the official llvm-mingw `20261104` UCRT Ubuntu x86_64 toolchain, Clang target `x86_64-w64-mingw32`. Exact command:

```sh
MINGW_CC=/workspace/work/toolchain/llvm-mingw-20261104-ucrt-ubuntu-22.04-x86_64/bin/x86_64-w64-mingw32-clang ./build.sh
```

`build.sh` first builds/runs the dependency-free portable C tests, then builds the EXE. Tests cover wire decoding, signed bounds, reset validation, sequence gaps/wrap, duplicate/out-of-order/stale rejection, session change, retired-session rejection, and timeout button clearing. Windows execution, actual SendInput behavior, network firewall behavior, physical pointer motion, and hardware timing remain unverified in this Linux environment. USB/ADB transport and a Windows precision-test GUI are not implemented in this receiver.

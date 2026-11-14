# Standalone HID transport checks

Run `scripts/test-hid.sh` from the source root with JDK 17 or newer (`javac` and `java` on PATH).

The script compiles the **production** `HidTransport.java` against small Android API doubles in this directory. It does not compile a rewritten transport. No reflection or production changes are needed.

The checks parse the actual HID descriptor and verify its report ID, exact payload bit count, button fields, padding, relative axis usages and signed limits. Runtime tests exercise actual registration/connection callbacks, signed displacement splitting up to 64 reports, combined buttons and wheel, release, GET_REPORT, unsupported IDs, integer-overflow safety, failed/throwing `sendReport`, dropped input while disconnected, retry callbacks, reconnection button reset, boot protocol, unplug and close.

The doubles copy submitted payload bytes and expose API submissions for assertions. Their Handler executes immediate posts synchronously and delayed posts only when explicitly advanced; they do **not** model Android threading, Binder, Bluetooth queues, HID host acceptance, radio timing, real connection loss, or Windows pointer movement. A passing run verifies Java control flow and encoding contracts only. Device/host integration is still required.

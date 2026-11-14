#!/bin/sh
set -eu
cd "$(dirname "$0")"
mkdir -p build
cc -std=c11 -Wall -Wextra -Werror -O2 protocol_test.c -o build/protocol_test
./build/protocol_test
: "${MINGW_CC:=x86_64-w64-mingw32-gcc}"
"$MINGW_CC" -std=c11 -Wall -Wextra -Werror -O2 receiver.c -o build/GyroLabReceiver.exe -lws2_32 -luser32

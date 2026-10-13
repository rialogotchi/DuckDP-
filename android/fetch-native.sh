#!/bin/sh
# Downloads the two native projects the APK is built from (ByeDPI + hev-socks5-tunnel).
set -e
cd "$(dirname "$0")/app/src/main/jni"
[ -d byedpi ] || git clone --depth 1 https://github.com/hufrea/byedpi byedpi
[ -d hev-socks5-tunnel ] || git clone --depth 1 --recursive https://github.com/heiher/hev-socks5-tunnel hev-socks5-tunnel
echo "ok - now open android/ in Android Studio, or run: cd android && gradle assembleDebug"

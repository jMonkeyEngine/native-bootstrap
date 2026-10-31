#!/usr/bin/env bash
set -euo pipefail

: "${ANDROID_HOME:?ANDROID_HOME must point to an Android SDK}"
: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME must point to an Android NDK}"
repo=$(cd "$(dirname "$0")/../../../.." && pwd)
work=$(mktemp -d)
remote=""
cleanup() {
    if [[ -n "$remote" ]]; then adb shell rm -rf "$remote" || true; fi
    rm -rf "$work"
}
trap cleanup EXIT

mkdir -p "$work/classes" "$work/dex" "$work/native"
find "$repo/native-common/src/main/java" "$repo/native-os/src/main/java" \
    "$repo/native-directories/src/main/java" "$repo/native-loader/src/main/java" \
    "$repo/native-loader/src/test/android" -name '*.java' -print > "$work/sources"
javac --release 8 -d "$work/classes" @"$work/sources"
jar cf "$work/classes.jar" -C "$work/classes" .
"$ANDROID_HOME/build-tools/35.0.0/d8" --min-api 26 --lib "$ANDROID_HOME/platforms/android-35/android.jar" \
    --output "$work/dex" "$work/classes.jar"
abi=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')
case "$abi" in
    x86_64) compiler=x86_64-linux-android26-clang ;;
    arm64-v8a) compiler=aarch64-linux-android26-clang ;;
    *) echo "Unsupported Android test ABI: $abi" >&2; exit 1 ;;
esac
"$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/$compiler" \
    -shared -fPIC "$repo/native-loader/src/test/native/probe.c" -o "$work/native/libnativebootstrap_probe.so"
jar cf "$work/smoke.jar" -C "$work/dex" classes.dex -C "$work" native
remote=$(adb shell mktemp -d /data/local/tmp/native-bootstrap-XXXXXX | tr -d '\r')
adb push "$work/smoke.jar" "$remote/smoke.jar"
adb push "$work/native/libnativebootstrap_probe.so" "$remote/libnativebootstrap_probe.so"
adb shell "CLASSPATH='$remote/smoke.jar' app_process -Djava.library.path='$remote' /system/bin com.jme3.nativebootstrap.loader.AndroidSmoke '$remote/extraction'"

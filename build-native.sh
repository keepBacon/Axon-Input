#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
JNI_SRC="$ROOT/native/keyengine.cpp"
PROXY_SRC="$ROOT/native/sensitivityproxy.cpp"
GAMEPAD_MONITOR_SRC="$ROOT/native/gamepadmonitor.cpp"
OUT_DIR="$ROOT/app/src/main/jniLibs/arm64-v8a"
JNI_LIB="$OUT_DIR/libkeyengine.so"
PROXY_BIN="$OUT_DIR/libsensitivityproxy.so"
GAMEPAD_MONITOR_BIN="$OUT_DIR/libgamepadmonitor.so"

case "$(uname -m)" in
    aarch64|arm64) ;;
    *) echo "[Axon Input] 当前脚本只构建 arm64-v8a" >&2; exit 1 ;;
esac
command -v clang++ >/dev/null 2>&1 || { echo "[Axon Input] 未找到 clang++" >&2; exit 1; }
command -v javac >/dev/null 2>&1 || { echo "[Axon Input] 未找到 Java 21" >&2; exit 1; }

JAVA_BIN="$(readlink -f "$(command -v javac)")"
JAVA_HOME="$(cd "$(dirname "$JAVA_BIN")/.." && pwd)"
JNI_INCLUDE="$JAVA_HOME/include"
JNI_PLATFORM_INCLUDE="$JNI_INCLUDE/linux"
[ -f "$JNI_INCLUDE/jni.h" ] || { echo "[Axon Input] 找不到 jni.h" >&2; exit 1; }

mkdir -p "$OUT_DIR"

echo "[Axon Input] C++20 JNI -> libkeyengine.so"
clang++ \
    -std=c++20 -shared -fPIC -O2 \
    -fno-exceptions -fno-rtti -fvisibility=hidden -nostdlib++ \
    -Wl,--no-undefined -Wl,-soname,libkeyengine.so \
    -I"$JNI_INCLUDE" -I"$JNI_PLATFORM_INCLUDE" \
    "$JNI_SRC" -o "$JNI_LIB"

echo "[Axon Input] C++20 input proxy -> libsensitivityproxy.so"
clang++ \
    -std=c++20 -fPIE -pie -O2 \
    -fno-exceptions -fno-rtti -nostdlib++ \
    -Wl,--no-undefined \
    "$PROXY_SRC" -o "$PROXY_BIN"

chmod 755 "$PROXY_BIN"

echo "[Axon Input] C++20 gamepad monitor -> libgamepadmonitor.so"
clang++ \
    -std=c++20 -fPIE -pie -O2 \
    -fno-exceptions -fno-rtti -nostdlib++ \
    -Wl,--no-undefined \
    "$GAMEPAD_MONITOR_SRC" -o "$GAMEPAD_MONITOR_BIN"
chmod 755 "$GAMEPAD_MONITOR_BIN"

if command -v readelf >/dev/null 2>&1; then
    echo "[Axon Input] JNI dependencies:"
    readelf -d "$JNI_LIB" | grep NEEDED || true
    echo "[Axon Input] Proxy dependencies:"
    readelf -d "$PROXY_BIN" | grep NEEDED || true
    echo "[Axon Input] Gamepad monitor dependencies:"
    readelf -d "$GAMEPAD_MONITOR_BIN" | grep NEEDED || true
fi

echo "[Axon Input] Native build complete"

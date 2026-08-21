#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

fail() {
    echo "[Axon Input] ERROR: $*" >&2
    exit 1
}

REQUIRED_CMDS=(java javac jar keytool clang++ aapt2 d8 apksigner zip sha256sum)
MISSING=()
for cmd in "${REQUIRED_CMDS[@]}"; do
    command -v "$cmd" >/dev/null 2>&1 || MISSING+=("$cmd")
done
if [ "${#MISSING[@]}" -ne 0 ]; then
    echo "[Axon Input] Missing tools: ${MISSING[*]}" >&2
    echo "pkg install openjdk-21 clang aapt2 d8 apksigner zip coreutils -y" >&2
    exit 1
fi

JAVA_MAJOR="$(java -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')"
[ "$JAVA_MAJOR" = "21" ] || fail "需要 Java 21，当前 Java=$JAVA_MAJOR"

case "$(uname -m)" in
    aarch64|arm64) ;;
    *) fail "只支持 ARM64 Termux" ;;
esac

ANDROID_JAR=""
for candidate in \
    "${ANDROID_SDK_ROOT:-}/platforms/android-36/android.jar" \
    "${ANDROID_HOME:-}/platforms/android-36/android.jar" \
    "$HOME/android-sdk/platforms/android-36/android.jar"; do
    if [ -n "$candidate" ] && [ -f "$candidate" ]; then
        ANDROID_JAR="$candidate"
        break
    fi
done
if [ -z "$ANDROID_JAR" ]; then
    ANDROID_JAR="$(find "$HOME" "$PREFIX" -type f -path '*/platforms/android-36/android.jar' 2>/dev/null | head -n 1 || true)"
fi
[ -f "$ANDROID_JAR" ] || fail "找不到 Android 36 android.jar"

BUILD="$ROOT/out"
COMPILED_RES="$BUILD/compiled-res"
GEN="$BUILD/generated"
CLASSES="$BUILD/classes"
CLASSES_JAR="$BUILD/classes.jar"
DEX="$BUILD/dex"
APK_STAGE="$BUILD/apk-stage"
UNSIGNED="$BUILD/AxonInput-unsigned.apk"
FINAL_APK="$BUILD/AxonInput-debug.apk"

# 签名密钥保存在项目目录外，后续构建继续复用。
SIGNING_DIR="${AXON_SIGNING_DIR:-$HOME/.axon-input}"
KEYSTORE="$SIGNING_DIR/axon-input.keystore"
KEY_ALIAS="axoninput"
KEY_PASS="android"
CERT_DER="$BUILD/axon-input-cert.der"

rm -rf "$BUILD"
mkdir -p "$COMPILED_RES" "$GEN" "$CLASSES" "$DEX" "$APK_STAGE/lib/arm64-v8a" "$SIGNING_DIR"

if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair \
        -keystore "$KEYSTORE" \
        -storepass "$KEY_PASS" \
        -keypass "$KEY_PASS" \
        -alias "$KEY_ALIAS" \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -dname "CN=Axon Input,O=Axon Input,C=US" \
        -noprompt >/dev/null 2>&1
fi

keytool -exportcert \
    -keystore "$KEYSTORE" \
    -storepass "$KEY_PASS" \
    -alias "$KEY_ALIAS" \
    -file "$CERT_DER" >/dev/null 2>&1
CERT_SHA256="$(sha256sum "$CERT_DER" | cut -d' ' -f1)"
[ "${#CERT_SHA256}" -eq 64 ] || fail "无法计算签名证书 SHA-256"

echo "[Axon Input] Java 21 + C++20 / no Gradle"
echo "[Axon Input] Android jar: $ANDROID_JAR"

# 1）构建 Native C++
"$ROOT/build-native.sh"
NATIVE_LIB="$ROOT/app/src/main/jniLibs/arm64-v8a/libkeyengine.so"
PROXY_BIN="$ROOT/app/src/main/jniLibs/arm64-v8a/libsensitivityproxy.so"
GAMEPAD_MONITOR_BIN="$ROOT/app/src/main/jniLibs/arm64-v8a/libgamepadmonitor.so"
[ -f "$NATIVE_LIB" ] || fail "C++ JNI 输出不存在"
[ -f "$PROXY_BIN" ] || fail "灵敏度代理输出不存在"
[ -f "$GAMEPAD_MONITOR_BIN" ] || fail "手柄监听输出不存在"

# 2）编译资源
# 编译完整 Android 资源，包括 PNG 图标。
# 不能只编译 XML，否则会漏掉 PNG 资源。
# 漏编译图标会导致 aapt2 链接失败。
aapt2 compile \
    --dir "$ROOT/app/src/main/res" \
    -o "$COMPILED_RES"
mapfile -t FLATS < <(find "$COMPILED_RES" -type f -name '*.flat' | sort)
[ "${#FLATS[@]}" -gt 0 ] || fail "资源编译失败"

aapt2 link \
    -o "$UNSIGNED" \
    --manifest "$ROOT/app/src/main/AndroidManifest.xml" \
    --java "$GEN" \
    --min-sdk-version 26 \
    --target-sdk-version 36 \
    -I "$ANDROID_JAR" \
    "${FLATS[@]}"

# 3）生成构建签名摘要。使用同一签名密钥。
mkdir -p "$GEN/com/axon/input"
cat > "$GEN/com/axon/input/BuildSignature.java" <<SIGEOF
package com.axon.input;

final class BuildSignature {
    private BuildSignature() {}
    static final String EXPECTED_SHA256 = "$CERT_SHA256";
}
SIGEOF

# 4）编译 Java 21
mapfile -t JAVA_SOURCES < <(find "$ROOT/app/src/main/java" "$GEN" -type f -name '*.java' | sort)
javac \
    -encoding UTF-8 \
    -source 21 \
    -target 21 \
    -classpath "$ANDROID_JAR" \
    -d "$CLASSES" \
    "${JAVA_SOURCES[@]}"
jar cf "$CLASSES_JAR" -C "$CLASSES" .

# 5）生成 DEX
d8 \
    --min-api 26 \
    --lib "$ANDROID_JAR" \
    --output "$DEX" \
    "$CLASSES_JAR"
[ -f "$DEX/classes.dex" ] || fail "D8 没有生成 classes.dex"

# 6）打包 DEX 和 JNI。extractNativeLibs=true，不需要 zipalign。
cp "$DEX/classes.dex" "$APK_STAGE/classes.dex"
cp "$NATIVE_LIB" "$APK_STAGE/lib/arm64-v8a/libkeyengine.so"
cp "$PROXY_BIN" "$APK_STAGE/lib/arm64-v8a/libsensitivityproxy.so"
cp "$GAMEPAD_MONITOR_BIN" "$APK_STAGE/lib/arm64-v8a/libgamepadmonitor.so"
(
    cd "$APK_STAGE"
    zip -q -u "$UNSIGNED" classes.dex
    zip -q -u -r "$UNSIGNED" lib
)

# 7）使用同一证书签名 APK。
apksigner sign \
    --ks "$KEYSTORE" \
    --ks-key-alias "$KEY_ALIAS" \
    --ks-pass pass:"$KEY_PASS" \
    --key-pass pass:"$KEY_PASS" \
    --out "$FINAL_APK" \
    "$UNSIGNED"

apksigner verify "$FINAL_APK"
[ -f "$FINAL_APK" ] || fail "没有生成 APK"

# 8）复制签名 APK 到 Download 目录。
# 优先使用 Termux 共享存储路径，失败时使用 Android Download 路径。
DOWNLOAD_DIR=""
for candidate in "$HOME/storage/downloads" "/storage/emulated/0/Download"; do
    if [ -d "$candidate" ] && [ -w "$candidate" ]; then
        DOWNLOAD_DIR="$candidate"
        break
    fi
done
[ -n "$DOWNLOAD_DIR" ] || fail "找不到可写的 Download 目录，请先执行 termux-setup-storage 并授予存储权限"

DOWNLOAD_APK="$DOWNLOAD_DIR/AxonInput.apk"
cp -f "$FINAL_APK" "$DOWNLOAD_APK"
[ -f "$DOWNLOAD_APK" ] || fail "APK 复制到 Download 失败"

echo "[Axon Input] BUILD SUCCESSFUL"
echo "[Axon Input] APK: $DOWNLOAD_APK"
echo "[Axon Input] Signing key: $KEYSTORE"

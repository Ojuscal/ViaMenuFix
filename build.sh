#!/usr/bin/env bash
# Via Menu Fix —— 免 Gradle 构建脚本（Linux / macOS / CI）
#
# 依赖：JDK 17、Android SDK（build-tools 34.x + platforms/android-34）、zip、keytool
# 用法：bash build.sh
# 可选环境变量：ANDROID_HOME / ANDROID_SDK_ROOT、VIAMENUFIX_KEYSTORE、VERSION_NAME、VERSION_CODE

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERSION_NAME="${VERSION_NAME:-1.0.0}"
VERSION_CODE="${VERSION_CODE:-1}"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
if [ ! -d "$SDK" ]; then
  echo "找不到 Android SDK: $SDK（请设置 ANDROID_HOME）" >&2
  exit 1
fi

BT=""
for d in $(ls -d "$SDK"/build-tools/34.* 2>/dev/null | sort -V -r); do BT="$d"; break; done
if [ -z "$BT" ]; then
  echo "找不到 $SDK/build-tools/34.*（sdkmanager --install 'build-tools;34.0.0'）" >&2
  exit 1
fi

ANDROID_JAR="$SDK/platforms/android-34/android.jar"
AAPT2="$BT/aapt2"
D8="$BT/d8"
ZIPALIGN="$BT/zipalign"
APKSIGNER="$BT/apksigner"

for f in "$ANDROID_JAR" "$AAPT2" "$D8" "$ZIPALIGN" "$APKSIGNER"; do
  [ -e "$f" ] || { echo "缺少构建依赖: $f" >&2; exit 1; }
done

KEYSTORE="${VIAMENUFIX_KEYSTORE:-$ROOT/keystore/viamenufix.jks}"
BUILD="$ROOT/build"
OUT="$ROOT/out"

echo "[0/6] 准备构建目录"
rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/dex" "$OUT"

echo "[1/6] aapt2 compile/link"
"$AAPT2" compile --dir "$ROOT/res" -o "$BUILD/res.zip"
"$AAPT2" link \
  -o "$BUILD/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$ROOT/AndroidManifest.xml" \
  --java "$BUILD/gen" \
  --min-sdk-version 19 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  "$BUILD/res.zip"

echo "[2/6] javac"
mapfile -t SOURCES < <(find "$ROOT/src" -name '*.java')
javac -source 8 -target 8 -encoding UTF-8 -nowarn \
  -bootclasspath "$ANDROID_JAR" \
  -cp "$ROOT/lib/api-82.jar" \
  -d "$BUILD/classes" "${SOURCES[@]}"

echo "[3/6] d8"
mapfile -t CLASSES < <(find "$BUILD/classes" -name '*.class')
"$D8" --lib "$ANDROID_JAR" --lib "$ROOT/lib/api-82.jar" --min-api 19 \
  --output "$BUILD/dex" "${CLASSES[@]}"

echo "[4/6] 组装 APK"
cp "$BUILD/base.apk" "$BUILD/unsigned.apk"
mkdir -p "$BUILD/assets"
cp "$ROOT/assets/xposed_init" "$BUILD/assets/xposed_init"
cp "$BUILD/dex/classes.dex" "$BUILD/classes.dex"
( cd "$BUILD" && zip -q -X unsigned.apk classes.dex assets/xposed_init )

echo "[5/6] zipalign"
"$ZIPALIGN" -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

echo "[6/6] apksigner"
if [ ! -f "$KEYSTORE" ]; then
  echo "  未找到密钥，生成自签密钥: $KEYSTORE"
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkeypair -keystore "$KEYSTORE" -alias viamenufix -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass android -keypass android \
    -dname 'CN=ViaMenuFix, O=local, C=CN'
fi
APK="$OUT/ViaMenuFix-$VERSION_NAME.apk"
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-key-alias viamenufix \
  --ks-pass pass:android --key-pass pass:android \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$APK" "$BUILD/aligned.apk"

"$APKSIGNER" verify --print-certs "$APK" | head -n 2
echo
echo "构建完成: $APK"
ls -lh "$APK" | awk '{print "大小: " $5}'

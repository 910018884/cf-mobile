#!/usr/bin/env bash
# 手写 Android 构建：aapt2 + javac + d8 + zipalign + apksigner（不用 Gradle）
set -euo pipefail

API="${API_LEVEL:-34}"
BT_VER="${BUILD_TOOLS:-34.0.0}"
HOME_SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
PKG="CFMobile-debug.apk"

if [ -z "$HOME_SDK" ]; then echo "ANDROID_HOME 未设置"; exit 1; fi
BT="$HOME_SDK/build-tools/$BT_VER"
PLAT="$HOME_SDK/platforms/android-$API/android.jar"

if [ ! -x "$BT/aapt2" ]; then
  echo "找不到 aapt2：$BT/aapt2"; echo "可用 build-tools：$(ls "$HOME_SDK/build-tools" 2>/dev/null)"
  BT=$(ls -1 "$HOME_SDK/build-tools" | tail -1); BT="$HOME_SDK/build-tools/$BT"
  echo "改用：$BT"
fi
[ -f "$PLAT" ] || { echo "找不到 $PLAT"; exit 1; }

rm -rf out
mkdir -p out/gen out/obj out/apk

echo "→ aapt2 compile"
"$BT/aapt2" compile --dir app/res -o out/res.zip

echo "→ aapt2 link"
"$BT/aapt2" link -o out/apk/base.apk -I "$PLAT" \
  --manifest app/AndroidManifest.xml \
  -R out/res.zip \
  --java out/gen \
  --min-sdk-version 21 --target-sdk-version "$API" \
  --version-code 1 --version-name 1.0 \
  --auto-add-overlay

echo "→ javac"
find app/src -name '*.java' > out/srcs.txt
find out/gen  -name 'R.java' >> out/srcs.txt
echo "  源文件 $(wc -l < out/srcs.txt) 个"
javac -nowarn -encoding UTF-8 -source 1.8 -target 1.8 \
  -bootclasspath "$PLAT" -d out/obj @out/srcs.txt

echo "→ d8"
find out/obj -name '*.class' > out/classes.txt
# shellcheck disable=SC2046
"$BT/d8" --lib "$PLAT" --min-api 21 --output out/apk/dex.zip $(cat out/classes.txt)

echo "→ 合入 classes.dex"
( cd out/apk && unzip -o -q dex.zip classes.dex && zip -q base.apk classes.dex )

echo "→ zipalign"
"$BT/zipalign" -f 4 out/apk/base.apk out/apk/aligned.apk

echo "→ 签名"
keytool -genkeypair -keystore out/debug.keystore -alias dsh \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass android -keypass android \
  -dname "CN=DSH,OU=DSH,O=DSH,C=US" >/dev/null 2>&1
"$BT/apksigner" sign --ks out/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --out "out/$PKG" out/apk/aligned.apk
"$BT/apksigner" verify --print-certs "out/$PKG"
ls -l "out/$PKG"
echo "完成：out/$PKG"

#!/usr/bin/env bash
# 本地 aarch64 构建：Debian 的 aapt2/zipalign/apksigner + build-tools 里的 d8.jar
set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-arm64}"
export PATH="$JAVA_HOME/bin:$PATH"

# Debian 的 aapt2 2.19 读不了 API 34 的 android.jar（RES_TABLE entry offsets overlap），
# 所以默认用 API 30 的平台包；需要时用 PLAT=... 覆盖。
PLAT="${PLAT:-}"
if [ -z "$PLAT" ]; then
  for c in /tmp/android30.jar /tmp/android-30/android.jar /tmp/android.jar; do
    [ -f "$c" ] && PLAT="$c" && break
  done
fi
PLAT="${PLAT:-/tmp/android.jar}"
D8="${D8:-/tmp/d8.jar}"
OUT=out/local
RES="$OUT/res.zip"
GEN="$OUT/gen"
OBJ="$OUT/obj"
DEX="$OUT/dex"
APK="$OUT/CFMobile-debug.apk"

[ -f "$PLAT" ] || { echo "缺平台 jar: $PLAT"; exit 1; }
[ -f "$D8" ] || { echo "缺 d8: $D8"; exit 1; }
rm -rf "$OUT"; mkdir -p "$GEN" "$OBJ" "$DEX" "$OUT/apk"

echo "== aapt2 compile =="
aapt2 compile --dir app/res -o "$RES"

echo "== aapt2 link =="
aapt2 link -I "$PLAT" \
  --manifest app/AndroidManifest.xml \
  --min-sdk-version 21 --target-sdk-version 34 \
  --version-code 1 --version-name 1.0 \
  --auto-add-overlay \
  --java "$GEN" \
  -o "$OUT/base.apk" \
  "$RES"

echo "== javac =="
find app/src "$GEN" -name '*.java' > "$OUT/srcs.txt"
xargs -a "$OUT/srcs.txt" javac -nowarn -encoding UTF-8 \
  -source 1.8 -target 1.8 -bootclasspath "$PLAT" -d "$OBJ" 2>&1 | grep -v 'bootstrap class path' || true
find "$OBJ" -name '*.class' > "$OUT/classes.txt"
echo "  classes: $(wc -l < "$OUT/classes.txt")"

echo "== d8 =="
mapfile -t CLS < "$OUT/classes.txt"
java -cp "$D8" com.android.tools.r8.D8 \
  --lib "$PLAT" --min-api 21 --output "$DEX" --release "${CLS[@]}"
ls -la "$DEX/classes.dex" | awk '{print "  classes.dex", $5, "bytes"}'

echo "== 注入 dex =="
python3 - "$OUT/base.apk" "$DEX/classes.dex" "$OUT/aligned.apk" <<'PY'
import sys, zipfile, shutil
base, dex, tmp = sys.argv[1], sys.argv[2], sys.argv[3]
zin = zipfile.ZipFile(base)
zout = zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED)
seen = set()
for it in zin.infolist():
    zout.writestr(it, zin.read(it.filename)); seen.add(it.filename)
if 'classes.dex' in seen:
    zout.close(); raise SystemExit('classes.dex 已存在于 base.apk')
zout.write(dex, 'classes.dex', zipfile.ZIP_STORED)
zout.close(); zin.close()
print('  条目:', len(seen) + 1)
PY

echo "== zipalign =="
zipalign -f 4 "$OUT/aligned.apk" "$OUT/final.apk"

echo "== sign =="
KS="$OUT/debug.keystore"
[ -f "$KS" ] || keytool -genkeypair -v -keystore "$KS" -alias androiddebugkey \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass android -keypass android \
  -dname "CN=DSH,OU=DSH,O=DSH,C=US" >/dev/null 2>&1
apksigner sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --min-sdk-version 21 --max-sdk-version 34 \
  --out "$APK" "$OUT/final.apk"
apksigner verify --print-certs "$APK" 2>&1 | head -4
python3 - "$APK" <<'PY'
import sys, zipfile, os
z = zipfile.ZipFile(sys.argv[1])
n = z.namelist()
need = ['classes.dex', 'AndroidManifest.xml', 'resources.arsc']
signed = [x for x in n if x.startswith('META-INF/') and x.endswith('.SF')]
print('  校验:', 'OK' if all(k in n for k in need) and signed else '缺签名或条目')
print('  大小: %.1f KB, 条目 %d' % (os.path.getsize(sys.argv[1]) / 1024, len(n)))
PY
echo "产物: $APK"

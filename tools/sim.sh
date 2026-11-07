#!/usr/bin/env bash
# 无头跑逻辑层：不装 APK 也能验证回合流程、AI、炸弹状态机
set -euo pipefail
cd "$(dirname "$0")/.."
J="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-arm64}/bin"
PLAT="${PLAT:-/tmp/android30.jar}"
OUT=$(mktemp -d)
"$J/javac" -nowarn -encoding UTF-8 -cp "$PLAT" -d "$OUT" \
  app/src/com/dsh/cf/*.java tools/sim/com/dsh/cf/*.java 2>&1 | grep -v 'bootstrap class path' || true
for c in com.dsh.cf.CFMap com.dsh.cf.Sim2 com.dsh.cf.Sim; do
  echo "===== $c ====="
  "$J/java" -cp "$OUT" "$c" | tail -n 14
done

#!/usr/bin/env bash
# Smoke-test the ACTUAL production JAR in a standalone Fabric dedicated server.
# This avoids development Yarn/Intermediary mapping mismatches in Cobblemon Kotlin reflection.
set -euo pipefail
ROOT="$(pwd)"
TARGET="$ROOT/smoke-server"
mkdir -p "$TARGET/mods"
curl -fLsS --retry 3 \
  "https://maven.fabricmc.net/net/fabricmc/fabric-installer/1.1.1/fabric-installer-1.1.1.jar" \
  -o "$ROOT/fabric-installer.jar"
java -jar "$ROOT/fabric-installer.jar" server -mcversion 1.21.1 -loader 0.18.4 \
  -downloadMinecraft -dir "$TARGET"
COBBLEMON_URL="$(curl -fLsS --retry 3 https://api.modrinth.com/v2/version/kF7CvxTo | jq -r '.files[] | select(.primary == true) | .url' | head -1)"
if [ -z "$COBBLEMON_URL" ]; then echo "Missing Cobblemon 1.7.3 download URL"; exit 1; fi
curl -fLsS --retry 3 "$COBBLEMON_URL" -o "$TARGET/mods/cobblemon.jar"
curl -fLsS --retry 3 \
  "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.116.6+1.21.1/fabric-api-0.116.6+1.21.1.jar" \
  -o "$TARGET/mods/fabric-api.jar"
curl -fLsS --retry 3 \
  "https://maven.fabricmc.net/net/fabricmc/fabric-language-kotlin/1.13.10+kotlin.2.3.20/fabric-language-kotlin-1.13.10+kotlin.2.3.20.jar" \
  -o "$TARGET/mods/fabric-language-kotlin.jar"
MOD_JAR="$(find "$ROOT/build/libs" -maxdepth 1 -type f -name 'cobblemon-duo-*.jar' ! -name '*sources*' | head -1)"
if [ -z "$MOD_JAR" ]; then echo "Missing Cobblemon Duo JAR"; exit 1; fi
cp "$MOD_JAR" "$TARGET/mods/cobblemon-duo.jar"
printf 'eula=true\n' > "$TARGET/eula.txt"
printf 'online-mode=false\n' > "$TARGET/server.properties"
cd "$TARGET"
echo "Starting production Fabric 1.21.1 dedicated server with Cobblemon 1.7.3..."
java -Xms1G -Xmx3G -jar fabric-server-launch.jar nogui > "$ROOT/run-smoke.log" 2>&1 &
SERVER_PID=$!
READY=0
for _ in $(seq 1 160); do
  if grep -Eq 'Done \([0-9.]+s\)!' "$ROOT/run-smoke.log"; then READY=1; break; fi
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then break; fi
  sleep 1
done
kill "$SERVER_PID" 2>/dev/null || true
wait "$SERVER_PID" 2>/dev/null || true
if [ "$READY" = 1 ]; then
  grep -E 'Done \([0-9.]+s\)!' "$ROOT/run-smoke.log" | tail -1
  echo "Production dedicated-server boot confirmed."
else
  echo "Production dedicated-server boot FAILED."
  tail -n 160 "$ROOT/run-smoke.log"
  exit 1
fi

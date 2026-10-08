#!/usr/bin/env bash
# Starts the engine exactly as the installed app does (bundled Java runtime, bundled jar,
# bundled UI) from electron-builder's unpacked output, and checks the API and UI respond.
# Catches a runtime missing a Java module, a wrong architecture, or a broken bundle.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENGINE_DIR="$(find "$ROOT/desktop/dist" -type d -path '*esources/engine' | head -1)"
[ -n "$ENGINE_DIR" ] || { echo "No unpacked app found under desktop/dist"; exit 1; }
RES="$(dirname "$ENGINE_DIR")"
JAVA="$RES/runtime/bin/java"; [ -x "$JAVA" ] || JAVA="$JAVA.exe"
echo "resources: $RES"
"$JAVA" -version
DATA="$(mktemp -d)"
LOG="$DATA/engine.log"
"$JAVA" -jar "$RES/engine/cassandra-studio-engine-all.jar" --port 18765 --token smoke --data-dir "$DATA" \
  --ui-dir "$RES/ui" --no-keyring > "$LOG" 2>&1 &
PID=$!
trap 'kill $PID 2>/dev/null || true' EXIT
for _ in $(seq 1 60); do grep -q STUDIO_ENGINE_READY "$LOG" && break; sleep 1; done
grep STUDIO_ENGINE_READY "$LOG" || { cat "$LOG"; echo "engine did not start"; exit 1; }
curl -fsS -H "Authorization: Bearer smoke" http://127.0.0.1:18765/api/info; echo
code=$(curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:18765/api/info)
[ "$code" = "401" ] || { echo "expected 401 without token, got $code"; exit 1; }
curl -fsS -o /dev/null http://127.0.0.1:18765/ && curl -fsS -o /dev/null http://127.0.0.1:18765/monaco/vs/loader.js
echo "packaged engine OK"

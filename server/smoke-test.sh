#!/usr/bin/env bash
# Smoke test of the Studio Server image: starts it with TLS, creates an API token with the CLI inside the
# container, then checks /api/health (public), /api/info (token; the token's user is the actor) and that
# requests without a token are refused. Usage: server/smoke-test.sh IMAGE [HOST_PORT]
set -euo pipefail
IMAGE=${1:?image}
PORT=${2:-18443}
NAME=studio-server-smoke-$$
WORK=$(mktemp -d)
cleanup() {
  docker logs "$NAME" > "$WORK/server.log" 2>&1 || true
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  [ "${KEEP:-}" = 1 ] || rm -rf "$WORK"
}
trap cleanup EXIT

keytool -genkeypair -alias server -keyalg EC -dname CN=localhost -ext SAN=dns:localhost,ip:127.0.0.1 -validity 7 \
  -keystore "$WORK/server.p12" -storetype PKCS12 -storepass smoke-pass >/dev/null 2>&1
chmod 644 "$WORK/server.p12" # test keystore only: readable by the image's unprivileged user

docker run -d --name "$NAME" -p "127.0.0.1:$PORT:8443" \
  -v "$WORK/server.p12:/etc/cassandra-studio/server.p12:ro" \
  -e STUDIO_TLS_KEYSTORE=/etc/cassandra-studio/server.p12 -e STUDIO_TLS_KEYSTOREPASSWORD=smoke-pass \
  "$IMAGE" >/dev/null

for _ in $(seq 1 60); do
  docker logs "$NAME" 2>&1 | grep -q STUDIO_SERVER_READY && break
  sleep 1
done
docker logs "$NAME" 2>&1 | grep STUDIO_SERVER_READY || { docker logs "$NAME"; echo "server did not start"; exit 1; }

uid=$(docker exec "$NAME" id -u)
[ "$uid" != 0 ] || { echo "runs as root"; exit 1; }

TOKEN=$(docker exec "$NAME" studio-server token add smoke --label ci 2>/dev/null)
case "$TOKEN" in cst_*) ;; *) echo "no token: $TOKEN"; exit 1 ;; esac

curl -fsSk "https://127.0.0.1:$PORT/api/health" | grep -q '"status":"UP"'
code=$(curl -sk -o /dev/null -w '%{http_code}' "https://127.0.0.1:$PORT/api/info")
[ "$code" = 401 ] || { echo "expected 401 without a token, got $code"; exit 1; }
info=$(curl -fsSk -H "Authorization: Bearer $TOKEN" "https://127.0.0.1:$PORT/api/info")
echo "$info"
echo "$info" | grep -q '"actor":"smoke"'
curl -fsSk -H "Authorization: Bearer $TOKEN" "https://127.0.0.1:$PORT/api/audit/verify" | grep -q '"ok":true'
code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT/api/health" || true)
[ "$code" != 200 ] || { echo "plain HTTP answered on the TLS port"; exit 1; }
docker exec "$NAME" studio-server health >/dev/null
echo "Studio Server image smoke test passed"

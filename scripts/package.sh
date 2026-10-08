#!/usr/bin/env bash
# Builds the desktop installers for the current OS:
#   engine jar -> jlink'd Java runtime -> UI -> Electron installer(s) in desktop/dist.
# Usage: scripts/package.sh [electron-builder args, e.g. --linux AppImage]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RES="$ROOT/desktop/resources"
rm -rf "$RES" && mkdir -p "$RES/engine"

# STUDIO_VERSION (e.g. 0.1.0 from a v0.1.0 tag) stamps the engine and the installer.
VERSION="${STUDIO_VERSION:-}"
if [ -n "$VERSION" ]; then
  (cd "$ROOT/desktop" && npm version "$VERSION" --no-git-tag-version --allow-same-version >/dev/null)
fi

echo "==> engine"
(cd "$ROOT/engine" && ./gradlew --no-daemon -q shadowJar ${VERSION:+-Pversion=$VERSION})
cp "$ROOT/engine/build/libs/cassandra-studio-engine-all.jar" "$RES/engine/"

echo "==> java runtime (jlink)"
# Modules the engine needs: driver (netty, sasl/gssapi), JMX over RMI, SQLite (java.sql), keyring (JNA).
MODULES=java.base,java.compiler,java.desktop,java.logging,java.management,java.management.rmi,java.naming,java.net.http,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.transaction.xa,java.xml,jdk.crypto.ec,jdk.management,jdk.naming.dns,jdk.naming.rmi,jdk.unsupported,jdk.zipfs,jdk.localedata
JLINK="${JAVA_HOME:?JAVA_HOME must point to a JDK 21}/bin/jlink"
[ -x "$JLINK" ] || JLINK="$JLINK.exe"
"$JLINK" --add-modules "$MODULES" --strip-debug --no-header-files \
  --no-man-pages --compress=zip-6 --output "$RES/runtime"

echo "==> ui"
(cd "$ROOT/ui" && npm ci --no-audit --no-fund && npm run build)
cp -r "$ROOT/ui/dist" "$RES/ui"

echo "==> installer"
cd "$ROOT/desktop"
npm ci --no-audit --no-fund
npx electron-builder --publish never "$@"
ls -la dist

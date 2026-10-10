#!/usr/bin/env bash
# Installs each Linux package on a fresh container (deb on Ubuntu, rpm on Rocky Linux, AppImage
# on a bare Ubuntu with only the usual desktop libraries), starts the installed app under Xvfb,
# checks its window shows a usable UI with the expected engine version, then uninstalls it.
# Catches missing package dependencies, a broken install layout or a launcher that doesn't start.
# Usage: scripts/installer-check-linux.sh <dir with the .deb/.rpm/.AppImage> <version|""> [out dir]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="$(cd "$1" && pwd)"; VERSION="$2"; OUT="${3:-$DIST/installer-check}"
mkdir -p "$OUT"
APP_BIN="/opt/Cassandra Studio/cassandra-studio"
fail=0

# check <name> <image> <package file> <install commands> <app command> <uninstall commands>
check() {
  local name="$1" image="$2" pkg="$3" install="$4" app="$5" uninstall="$6" port=$((9300 + RANDOM % 500))
  local c="studio-installer-$name"
  echo "== $name: $(basename "$pkg") on $image"
  docker rm -f "$c" >/dev/null 2>&1 || true
  docker run -d --name "$c" --network host -v "$pkg:/pkg/$(basename "$pkg"):ro" "$image" sleep 3600 >/dev/null
  if ! docker exec "$c" bash -c "$install" > "$OUT/$name-install.log" 2>&1; then
    echo "   install FAILED"; tail -20 "$OUT/$name-install.log"; fail=1; docker rm -f "$c" >/dev/null; return
  fi
  echo "   installed"
  docker exec -d "$c" bash -c "Xvfb :99 -screen 0 1500x950x24 >/tmp/xvfb.log 2>&1 & sleep 1; \
    DISPLAY=:99 $app --no-sandbox --remote-debugging-port=$port >/tmp/app.log 2>&1"
  # Fails fast when the app or its engine dies (missing library, wrong glibc) instead of waiting out the timeouts.
  ( for _ in $(seq 180); do
      sleep 1
      if docker exec "$c" grep -qE "GLIBC_[0-9.]+' not found|error while loading shared libraries|Engine did not start" /tmp/app.log 2>/dev/null; then
        pkill -f "[i]nstalled-window.mjs http://127.0.0.1:$port" || true; exit
      fi
    done ) & local watcher=$!
  if (cd "$ROOT/ui" && timeout 200 node tests/installed-window.mjs "http://127.0.0.1:$port" "$VERSION" "$OUT/$name.png"); then
    echo "   window OK"
  else
    echo "   window FAILED"; docker exec "$c" bash -c "grep -E \"not found|error while loading|Engine did not start|engine stopped|Error:\" /tmp/app.log | head -10; \
      echo '-- last lines:'; grep -v '^/tmp/appimage_extracted' /tmp/app.log | grep -v dbus | tail -15" || true; fail=1
  fi
  kill "$watcher" 2>/dev/null || true
  docker exec "$c" bash -c "pkill -f '[c]assandra-studio' || true; sleep 2; pgrep -af '[c]assandra-studio-engine' && echo 'engine left running' || true"
  if [ -n "$uninstall" ]; then
    if docker exec "$c" bash -c "$uninstall && test ! -e '$APP_BIN'" > "$OUT/$name-uninstall.log" 2>&1; then
      echo "   uninstalled cleanly"
    else
      echo "   uninstall FAILED"; tail -20 "$OUT/$name-uninstall.log"; fail=1
    fi
  fi
  docker rm -f "$c" >/dev/null
}

deb="$(ls "$DIST"/*.deb 2>/dev/null | head -1 || true)"
rpm="$(ls "$DIST"/*.rpm 2>/dev/null | head -1 || true)"
appimage="$(ls "$DIST"/*.AppImage 2>/dev/null | head -1 || true)"

[ -n "$deb" ] && check deb ubuntu:22.04 "$deb" \
  "export DEBIAN_FRONTEND=noninteractive; apt-get update -q && apt-get install -y -q /pkg/$(basename "$deb") xvfb procps \
   && test -x '$APP_BIN' && ls /usr/share/applications/ | grep -i cassandra" \
  "'$APP_BIN'" \
  "apt-get remove -y -q cassandra-studio"

[ -n "$rpm" ] && check rpm rockylinux:9 "$rpm" \
  "dnf install -y -q /pkg/$(basename "$rpm") xorg-x11-server-Xvfb procps-ng && test -x '$APP_BIN' \
   && ls /usr/share/applications/ | grep -i cassandra" \
  "'$APP_BIN'" \
  "dnf remove -y -q cassandra-studio"

# AppImage: no FUSE in containers, so it is extracted and run; only common desktop libraries installed.
[ -n "$appimage" ] && check appimage ubuntu:22.04 "$appimage" \
  "export DEBIAN_FRONTEND=noninteractive; apt-get update -q && apt-get install -y -q --no-install-recommends \
   libgtk-3-0 libnss3 libasound2 libgbm1 libxss1 libxtst6 xvfb procps ca-certificates && cp /pkg/*.AppImage /tmp/app.AppImage && chmod +x /tmp/app.AppImage" \
  "APPIMAGE_EXTRACT_AND_RUN=1 /tmp/app.AppImage" \
  ""

[ -n "$deb$rpm$appimage" ] || { echo "no Linux packages in $DIST"; exit 1; }
[ "$fail" = 0 ] && echo "all Linux installers OK" || { echo "installer check FAILED"; exit 1; }

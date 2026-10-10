#!/usr/bin/env bash
# Brings the local test environment back after a machine restart: starts the Docker daemon if it
# is not running (clearing stale pid files a hard restart leaves behind), then the test-env
# clusters with all profiles, and waits until every node is up.
# Usage: scripts/dev-up.sh [timeout-seconds]   (default 400)
set -euo pipefail
cd "$(dirname "$0")/.."

if ! docker info >/dev/null 2>&1; then
  if ! pgrep -x dockerd >/dev/null; then
    rm -f /run/docker/containerd/containerd.pid /var/run/docker/containerd/containerd.pid /var/run/docker.pid
    (dockerd > "${TMPDIR:-/tmp}/dockerd.log" 2>&1 &)
  fi
  for _ in $(seq 1 60); do docker info >/dev/null 2>&1 && break; sleep 1; done
  docker info >/dev/null 2>&1 || { echo "Docker did not start; see ${TMPDIR:-/tmp}/dockerd.log" >&2; exit 1; }
fi

[ -f test-env/ssh/id_test ] && [ -f test-env/certs/node.pem ] || test-env/make-certs.sh
PROFILES="${PROFILES:-secure jmx}" test-env/wait-ready.sh "${1:-400}"

#!/usr/bin/env bash
# Waits until test-env is usable: every container healthy, AND the seed sees all three
# acme-core nodes (dc_east x2, dc_west x1) as UN. A node can be healthy before the seed's
# gossip has seen it, so container health alone is not enough.
# Usage: test-env/wait-ready.sh [timeout_seconds]   (starts the containers if needed)
set -euo pipefail
cd "$(dirname "$0")"
TIMEOUT="${1:-300}"
start=$(date +%s)
docker compose --profile secure up -d --wait --wait-timeout "$TIMEOUT"
while true; do
  up=$(docker compose exec -T east1 nodetool status 2>/dev/null | grep -c '^UN' || true)
  [ "$up" = "3" ] && break
  if [ $(( $(date +%s) - start )) -ge "$TIMEOUT" ]; then
    echo "only $up of 3 acme-core nodes are UN after ${TIMEOUT}s"
    docker compose exec -T east1 nodetool status || true
    exit 1
  fi
  sleep 2
done
echo "test-env ready in $(( $(date +%s) - start ))s (3/3 acme-core nodes UN, 3.11 and 5.0 healthy)"

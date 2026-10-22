#!/usr/bin/env bash
#
# Load test: verify the service sustains ~10,000 read requests/minute on the
# Redis-cached GET /contents/{id} endpoint.
#
# Uses k6 (grafana/k6 image) running in a throwaway Docker container attached
# to the compose network, so it can reach the app by its service name.
# Rate is a constant 170 req/s (= 10,200/min) for 2 minutes.
#
# Prerequisites:
#   1. docker compose up --build   (stack running)
#   2. at least one content exists: POST /api/v1/cms/contents ... then pass its id below
#   3. optional: `docker stats` in a second terminal to watch PostgreSQL/Redis load
#
# Usage: ./scripts/load-test-10k-per-min.sh [content-id]
#   e.g. ./scripts/load-test-10k-per-min.sh 1

set -euo pipefail

ID="${1:-1}"
NETWORK="content-management-service_default"   # docker compose network; check with: docker network ls
URL="http://app:8080/api/v1/cms/contents/$ID"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Optional but recommended: prove the cache is hot BEFORE loading.
echo "==> Warm-up GET (populates the cache)..."
curl -sS "http://localhost:8080/api/v1/cms/contents/$ID" > /dev/null
echo "==> Redis key state:"
docker exec -i content-management-service-redis-1 redis-cli TTL "cms:content:$ID" || true

echo
echo "==> Running 170 req/s (~10,200 req/min) for 2 minutes against $URL"
echo "    watch 'docker stats' in another terminal: app busy, postgres/redis near-idle = cache working"
echo

# Git Bash mangles Windows paths passed to Docker; give it a Windows-style
# path and disable MSYS path conversion for this command.
SCRIPT_WIN="$(cygpath -w "$SCRIPT_DIR/k6-load.js")"
MSYS_NO_PATHCONV=1 docker run --rm --network "$NETWORK" \
    -v "$SCRIPT_WIN:/load.js" \
    -e TARGET_URL="$URL" \
    grafana/k6 run /load.js

echo
echo "==> Post-check: Redis hit counters after the run:"
docker exec -i content-management-service-redis-1 redis-cli INFO stats \
    | grep -E 'keyspace_hits|keyspace_misses'
echo
echo "If PostgreSQL CPU in 'docker stats' stayed near zero while serving"
echo "~10k req/min, the cache-aside design is doing its job."

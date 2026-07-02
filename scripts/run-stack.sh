#!/usr/bin/env sh
# Run the full stack (infra + backend + gateway + frontend) with locally built images.
# Runs attached — Ctrl+C stops everything. See docs/RUNNING.md.
#
#   ./scripts/run-stack.sh                                   # spring-kotlin + yarp + react
#   BUILD=1 ./scripts/run-stack.sh                           # docker build first
#   OBSERVABILITY=1 ./scripts/run-stack.sh                   # + Grafana on :3000
#   ./scripts/run-stack.sh <backend> <gateway> <frontend>    # other combos
set -eu

BACKEND="${1:-spring-kotlin}"
GATEWAY="${2:-yarp}"
FRONTEND="${3:-react}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

if [ "${BUILD:-0}" = "1" ]; then
    "$ROOT/scripts/build-images.sh" "$BACKEND" "$GATEWAY" "$FRONTEND"
fi

export BACKEND_IMAGE="stackverse/backend-${BACKEND}:local"
export GATEWAY_IMAGE="stackverse/gateway-${GATEWAY}:local"
export FRONTEND_IMAGE="stackverse/frontend-${FRONTEND}:local"

cd "$ROOT"
if [ "${OBSERVABILITY:-0}" = "1" ]; then
    OTEL_SDK_DISABLED=false docker compose --profile app --profile observability up
else
    docker compose --profile app up
fi

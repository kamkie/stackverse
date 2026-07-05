#!/usr/bin/env sh
# Trace-based observability tests against a composed stack with telemetry on.
# Runs attached and stops started containers when the Tracetest runner exits.
#
#   ./scripts/tracetest-otel.sh
#   BUILD=1 ./scripts/tracetest-otel.sh
#   ./scripts/tracetest-otel.sh <backend> <gateway> <frontend>
set -eu

if [ "${1:-}" = "--help" ] || [ "${1:-}" = "-h" ]; then
    sed -n '1,8p' "$0"
    exit 0
fi

BACKEND="${1:-spring-kotlin}"
GATEWAY="${2:-yarp}"
FRONTEND="${3:-react}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROJECT="${STACKVERSE_TRACETEST_PROJECT:-stackverse-tracetest}"

if [ "${BUILD:-0}" = "1" ]; then
    "$ROOT/scripts/build-images.sh" "$BACKEND" "$GATEWAY" "$FRONTEND"
fi

export BACKEND_IMAGE="stackverse/backend-${BACKEND}:local"
export GATEWAY_IMAGE="stackverse/gateway-${GATEWAY}:local"
export FRONTEND_IMAGE="stackverse/frontend-${FRONTEND}:local"
export OTEL_SDK_DISABLED=false

cd "$ROOT"
compose() {
    docker compose \
        -p "$PROJECT" \
        -f compose.yaml \
        -f testing/tracetest-otel/compose.yaml \
        --profile app \
        --profile observability \
        --profile tracetest \
        "$@"
}

stop_started() {
    compose stop >/dev/null 2>&1 || true
}

compose down --remove-orphans

trap stop_started EXIT INT TERM
set +e
compose \
    up \
    --force-recreate \
    --abort-on-container-exit \
    --exit-code-from tracetest-run
status=$?
set -e
trap - EXIT INT TERM
stop_started
exit "$status"

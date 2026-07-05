#!/usr/bin/env bash
# OWASP ZAP baseline smoke scan against a RUNNING Stackverse gateway.
# STACKVERSE_URL defaults to http://localhost:8000. Extra arguments are passed
# to zap-baseline.py.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SUITE="$ROOT/testing/zap-security"

STACKVERSE_URL="${STACKVERSE_URL:-http://localhost:8000}"
ZAP_TARGET_URL="${ZAP_TARGET_URL:-}"
ZAP_DOCKER_IMAGE="${ZAP_DOCKER_IMAGE:-ghcr.io/zaproxy/zaproxy:stable}"
ZAP_REPORT_DIR="${ZAP_REPORT_DIR:-$SUITE/reports}"
ZAP_CONFIG_FILE="${ZAP_CONFIG_FILE:-$SUITE/zap-baseline.conf}"
ZAP_SPIDER_MINUTES="${ZAP_SPIDER_MINUTES:-1}"
ZAP_MAX_MINUTES="${ZAP_MAX_MINUTES:-5}"
ZAP_FAIL_ON_WARNINGS="${ZAP_FAIL_ON_WARNINGS:-false}"
ZAP_DOCKER_NETWORK="${ZAP_DOCKER_NETWORK:-}"

is_truthy() {
    case "${1:-}" in
        1|true|TRUE|True|yes|YES|Yes|on|ON|On) return 0 ;;
        *) return 1 ;;
    esac
}

container_target_url() {
    local url="$1"
    local converted="$url"
    local nocasematch_was_set=0

    if shopt -q nocasematch; then
        nocasematch_was_set=1
    fi
    shopt -s nocasematch

    if [[ "$url" =~ ^(https?://)(localhost|127\.0\.0\.1)([:/?#].*)?$ ]]; then
        converted="${BASH_REMATCH[1]}host.docker.internal${BASH_REMATCH[3]:-}"
    fi

    if [ "$nocasematch_was_set" -eq 0 ]; then
        shopt -u nocasematch
    fi

    printf '%s\n' "$converted"
}

if [ -z "$ZAP_TARGET_URL" ]; then
    ZAP_TARGET_URL="$(container_target_url "$STACKVERSE_URL")"
fi

mkdir -p "$ZAP_REPORT_DIR"

docker_args=(
    run
    --rm
    --add-host
    host.docker.internal:host-gateway
    -v
    "$ZAP_REPORT_DIR:/zap/wrk:rw"
    -v
    "$ZAP_CONFIG_FILE:/zap/config/zap-baseline.conf:ro"
)

if [ -n "$ZAP_DOCKER_NETWORK" ]; then
    docker_args+=(--network "$ZAP_DOCKER_NETWORK")
fi

zap_args=(
    zap-baseline.py
    -t "$ZAP_TARGET_URL"
    -m "$ZAP_SPIDER_MINUTES"
    -T "$ZAP_MAX_MINUTES"
    -c /zap/config/zap-baseline.conf
    -r zap-baseline.html
    -w zap-baseline.md
    -J zap-baseline.json
    -s
)

if ! is_truthy "$ZAP_FAIL_ON_WARNINGS"; then
    zap_args+=(-I)
fi

zap_args+=("$@")

echo "Running OWASP ZAP baseline scan"
echo "  STACKVERSE_URL: $STACKVERSE_URL"
echo "  ZAP target URL: $ZAP_TARGET_URL"
echo "  reports:        $ZAP_REPORT_DIR"
echo "  image:          $ZAP_DOCKER_IMAGE"

exec docker "${docker_args[@]}" "$ZAP_DOCKER_IMAGE" "${zap_args[@]}"

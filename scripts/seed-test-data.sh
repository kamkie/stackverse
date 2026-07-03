#!/usr/bin/env bash
# Seed a running Stackverse backend with repeatable local demo data.
# Defaults: BACKEND_URL=http://localhost:8080, KEYCLOAK_URL=http://localhost:8180.
# Uses the dev-only stackverse-conformance Keycloak client and the public API.
set -eu

usage() {
    cat >&2 <<'EOF'
Usage: ./scripts/seed-test-data.sh [--backend-url URL] [--keycloak-url URL]

Environment defaults:
  BACKEND_URL=http://localhost:8080
  KEYCLOAK_URL=http://localhost:8180
  NODE_BIN=node
EOF
}

BACKEND_URL="${BACKEND_URL:-http://localhost:8080}"
KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8180}"
NODE_BIN="${NODE_BIN:-node}"

while [ "$#" -gt 0 ]; do
    case "$1" in
        --backend-url)
            BACKEND_URL="${2:?--backend-url requires a value}"
            shift 2
            ;;
        --keycloak-url)
            KEYCLOAK_URL="${2:?--keycloak-url requires a value}"
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            usage
            exit 2
            ;;
    esac
done

if ! command -v "$NODE_BIN" >/dev/null 2>&1; then
    echo "Node.js 18+ is required to run the seed helper." >&2
    exit 1
fi
NODE_VERSION="$("$NODE_BIN" --version 2>/dev/null || true)"
NODE_MAJOR="${NODE_VERSION#v}"
NODE_MAJOR="${NODE_MAJOR%%.*}"
case "$NODE_MAJOR" in
    ''|*[!0-9]*)
        echo "Node.js 18+ is required to run the seed helper; found ${NODE_VERSION:-unknown}." >&2
        exit 1
        ;;
esac
if [ "$NODE_MAJOR" -lt 18 ]; then
    echo "Node.js 18+ is required to run the seed helper; found $NODE_VERSION." >&2
    exit 1
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HELPER="$ROOT/tools/seed-test-data.mjs"
if command -v wslpath >/dev/null 2>&1; then
    NODE_PLATFORM="$("$NODE_BIN" -p 'process.platform' 2>/dev/null || true)"
    if [ "$NODE_PLATFORM" = "win32" ]; then
        HELPER="$(wslpath -w "$HELPER")"
        WSLENV="BACKEND_URL:KEYCLOAK_URL${WSLENV:+:$WSLENV}"
        export WSLENV
    fi
fi
export BACKEND_URL KEYCLOAK_URL
exec "$NODE_BIN" "$HELPER"

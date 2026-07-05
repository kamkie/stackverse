#!/usr/bin/env sh
# Hurl API showcase against a RUNNING backend (no gateway or frontend needed).
# BACKEND_URL / KEYCLOAK_URL override the defaults http://localhost:8080 /
# http://localhost:8180. Extra arguments after `--` are passed to `hurl`.
set -eu

usage() {
    cat >&2 <<'EOF'
Usage: ./scripts/hurl-api.sh [--backend-url URL] [--keycloak-url URL] [--run-id ID] [-- HURL_ARGS...]

Environment defaults:
  BACKEND_URL=http://localhost:8080
  KEYCLOAK_URL=http://localhost:8180
  HURL_RUN_ID=hurl-<timestamp>-<pid>
EOF
}

BACKEND_URL="${BACKEND_URL:-http://localhost:8080}"
KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8180}"
HURL_RUN_ID="${HURL_RUN_ID:-}"

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
        --run-id)
            HURL_RUN_ID="${2:?--run-id requires a value}"
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        --)
            shift
            break
            ;;
        *)
            usage
            exit 2
            ;;
    esac
done

if [ -z "$HURL_RUN_ID" ]; then
    HURL_RUN_ID="hurl-$(date -u +%Y%m%d%H%M%S)-$$"
fi
case "$HURL_RUN_ID" in
    *[!a-z0-9-]*)
        echo "HURL_RUN_ID must contain only lowercase letters, digits, and hyphens because it is used in message keys; got '$HURL_RUN_ID'." >&2
        exit 1
        ;;
esac

if ! command -v hurl >/dev/null 2>&1; then
    echo "Hurl is required to run testing/hurl-api. Install it from https://hurl.dev/docs/installation.html and rerun this helper." >&2
    exit 1
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/testing/hurl-api"
exec hurl --test \
    --variable "backend_url=$BACKEND_URL" \
    --variable "keycloak_url=$KEYCLOAK_URL" \
    --variable "run_id=$HURL_RUN_ID" \
    "$@" \
    stackverse-showcase.hurl

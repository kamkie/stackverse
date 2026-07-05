#!/usr/bin/env sh
# k6 system smoke and light-load showcase against a RUNNING stack
# (gateway on http://localhost:8000 unless STACKVERSE_URL says otherwise).
# Extra arguments are passed to `k6 run`.
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SUITE="$ROOT/testing/k6-system"
K6_BIN="${K6_BIN:-k6}"

"$K6_BIN" version

if [ "${K6_SKIP_SMOKE:-false}" != "true" ]; then
    "$K6_BIN" run "$@" "$SUITE/smoke.js"
fi

exec "$K6_BIN" run "$@" "$SUITE/light-load.js"

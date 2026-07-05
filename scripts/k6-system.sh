#!/usr/bin/env sh
# k6 system smoke and light-load showcase against a RUNNING stack
# (gateway on http://localhost:8000 unless STACKVERSE_URL says otherwise).
# Extra arguments are passed to `k6 run`.
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SUITE="$ROOT/testing/k6-system"
K6_BIN="${K6_BIN:-k6}"
SUMMARY_DIR="${K6_SUMMARY_DIR:-}"

run_k6() {
    script_name="$1"
    summary_name="$2"
    shift 2

    if [ -n "$SUMMARY_DIR" ]; then
        mkdir -p "$SUMMARY_DIR"
        "$K6_BIN" run "$@" --summary-export "$SUMMARY_DIR/$summary_name" "$SUITE/$script_name"
    else
        "$K6_BIN" run "$@" "$SUITE/$script_name"
    fi
}

"$K6_BIN" version

if [ "${K6_SKIP_SMOKE:-false}" != "true" ]; then
    run_k6 smoke.js smoke-summary.json "$@"
fi

run_k6 light-load.js light-load-summary.json "$@"

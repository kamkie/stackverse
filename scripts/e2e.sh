#!/usr/bin/env bash
# Playwright end-to-end suite against a RUNNING stack (dev-stack or run-stack,
# gateway on http://localhost:8000 unless STACKVERSE_URL says otherwise).
# Extra arguments are passed to `playwright test` (e.g. ./scripts/e2e.sh --headed).
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/e2e"
yarn install --immutable
yarn playwright install chromium
exec yarn playwright test "$@"

#!/usr/bin/env bash
# Contract conformance suite against a RUNNING backend (no gateway or frontend
# needed — just the compose infra and one backend; BACKEND_URL / KEYCLOAK_URL
# override the defaults http://localhost:8080 / http://localhost:8180).
# Extra arguments are passed to `playwright test` (e.g. ./scripts/conformance.sh -g pagination).
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/conformance"
yarn install --immutable
exec yarn playwright test "$@"

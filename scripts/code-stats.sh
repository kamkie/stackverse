#!/usr/bin/env bash
# Per-variant code statistics for Stackverse. Thin wrapper around the single
# implementation in tools/code-stats.mjs (mirrors scripts/code-stats.ps1).
#
# Usage: ./scripts/code-stats.sh [--format table|markdown|json]
#                                [--component backend|gateway|frontend]...
#                                [--write FILE]
#
# Files come from `git ls-files`; line counts and languages from `tokei`.
# Requires Node.js 18+ and tokei on PATH.
set -eu

NODE_BIN="${NODE_BIN:-node}"

if ! command -v "$NODE_BIN" >/dev/null 2>&1; then
    echo "Node.js 18+ is required to run the code-stats helper." >&2
    exit 1
fi
NODE_VERSION="$("$NODE_BIN" --version 2>/dev/null || true)"
NODE_MAJOR="${NODE_VERSION#v}"
NODE_MAJOR="${NODE_MAJOR%%.*}"
case "$NODE_MAJOR" in
    ''|*[!0-9]*)
        echo "Node.js 18+ is required to run the code-stats helper; found ${NODE_VERSION:-unknown}." >&2
        exit 1
        ;;
esac
if [ "$NODE_MAJOR" -lt 18 ]; then
    echo "Node.js 18+ is required to run the code-stats helper; found $NODE_VERSION." >&2
    exit 1
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HELPER="$ROOT/tools/code-stats.mjs"
if command -v wslpath >/dev/null 2>&1; then
    NODE_PLATFORM="$("$NODE_BIN" -p 'process.platform' 2>/dev/null || true)"
    if [ "$NODE_PLATFORM" = "win32" ]; then
        HELPER="$(wslpath -w "$HELPER")"
    fi
fi
exec "$NODE_BIN" "$HELPER" "$@"

#!/usr/bin/env sh
# Schemathesis OpenAPI property tests against a RUNNING backend (no gateway or
# frontend needed). BACKEND_URL / KEYCLOAK_URL override the defaults
# http://localhost:8080 / http://localhost:8180. Extra arguments are passed to
# `st run`.
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SUITE="$ROOT/testing/schemathesis-api"
VENV="$SUITE/.venv-sh"
PYTHON="${PYTHON_BIN:-python3}"

venv_python() {
    if [ -x "$VENV/bin/python" ]; then
        printf '%s\n' "$VENV/bin/python"
        return 0
    fi
    if [ -x "$VENV/Scripts/python.exe" ]; then
        printf '%s\n' "$VENV/Scripts/python.exe"
        return 0
    fi
    return 1
}

if ! VENV_PYTHON="$(venv_python)"; then
    "$PYTHON" -m venv "$VENV"
    VENV_PYTHON="$(venv_python)" || {
        echo "virtualenv was created but no Python executable was found under $VENV" >&2
        exit 1
    }
fi

"$VENV_PYTHON" -m pip install --disable-pip-version-check -r "$SUITE/requirements.txt"
cd "$SUITE"
exec "$VENV_PYTHON" -m stackverse_schemathesis "$@"

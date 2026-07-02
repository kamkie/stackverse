#!/usr/bin/env bash
# Full dev mode: Docker infra + each module (backend, gateway, frontend) as a
# dev process in its own Windows Terminal tab, output tee'd to .logs/<module>.log
# at the repo root. Bash twin of dev-stack.ps1 — see AGENTS.md, "Full dev mode".
# On Windows run from Git Bash (wt and docker on PATH).
#
#   ./scripts/dev-stack.sh                # start infra, open the three tabs
#   ./scripts/dev-stack.sh --tab backend  # internal: run one module here (tee'd)
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG_DIR="$ROOT/.logs"

if [ "${1:-}" = "--tab" ]; then
    MODULE="${2:?module name required}"
    mkdir -p "$LOG_DIR"
    LOG="$LOG_DIR/$MODULE.log"
    case "$MODULE" in
        backend)
            cd "$ROOT/backends/spring-kotlin"
            ./gradlew bootRun 2>&1 | tee "$LOG"
            ;;
        gateway)
            export FRONTEND_URL="http://localhost:5173"
            cd "$ROOT/gateways/yarp"
            dotnet run --project src/StackverseGateway 2>&1 | tee "$LOG"
            ;;
        frontend)
            export VITE_API_MOCK=false
            cd "$ROOT/frontends/react"
            yarn dev 2>&1 | tee "$LOG"
            ;;
        *)
            echo "unknown module: $MODULE" >&2
            exit 1
            ;;
    esac
    exit 0
fi

cd "$ROOT"
docker compose up -d

echo "Waiting for Keycloak to become healthy..."
status=unknown
for _ in $(seq 1 36); do
    status="$(docker inspect --format '{{.State.Health.Status}}' stackverse-keycloak-1)"
    [ "$status" = "healthy" ] && break
    sleep 5
done
if [ "$status" != "healthy" ]; then
    echo "Keycloak not healthy after 180s (status: $status)" >&2
    exit 1
fi

# one wt invocation per tab — wt splits inline commands on ';', so each tab
# re-enters this script instead (see AGENTS.md, "Windows Terminal pitfalls");
# cygpath makes $0 readable by the fresh bash wt spawns outside this MSYS env
SELF="$(cygpath -m "$0" 2>/dev/null || echo "$0")"
for module in backend gateway frontend; do
    wt -w stackverse new-tab --title "$module" --suppressApplicationTitle \
        bash "$SELF" --tab "$module"
done
echo "Tabs launched. App: http://localhost:8000 - logs: $LOG_DIR/<module>.log"

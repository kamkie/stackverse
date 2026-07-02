#!/usr/bin/env sh
# Build local Docker images for one backend + one gateway + one frontend implementation.
#
#   ./scripts/build-images.sh                       # spring-kotlin + yarp + react
#   ./scripts/build-images.sh go spring-cloud-gateway angular
#
# Conventions (see backends/README.md, gateways/README.md, frontends/README.md):
#   - backend images build with the REPO ROOT as context (they bundle spec/messages)
#   - gateway images build with their own directory as context
#   - frontend images build with the REPO ROOT as context (they bundle spec/design)
set -eu

BACKEND="${1:-spring-kotlin}"
GATEWAY="${2:-yarp}"
FRONTEND="${3:-react}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

docker build -t "stackverse/backend-${BACKEND}:local" -f "$ROOT/backends/$BACKEND/Dockerfile" "$ROOT"
docker build -t "stackverse/gateway-${GATEWAY}:local" "$ROOT/gateways/$GATEWAY"
docker build -t "stackverse/frontend-${FRONTEND}:local" -f "$ROOT/frontends/$FRONTEND/Dockerfile" "$ROOT"

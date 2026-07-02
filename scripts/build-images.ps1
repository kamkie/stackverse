#!/usr/bin/env pwsh
# Build local Docker images for one backend + one gateway + one frontend implementation.
#
#   ./scripts/build-images.ps1                     # spring-kotlin + yarp + react
#   ./scripts/build-images.ps1 -Backend go -Gateway spring-cloud-gateway
#
# Conventions (see backends/README.md, gateways/README.md, frontends/README.md):
#   - backend images build with the REPO ROOT as context (they bundle spec/messages)
#   - gateway images build with their own directory as context
#   - frontend images build with the REPO ROOT as context (they bundle spec/design)
param(
    [string]$Backend = "spring-kotlin",
    [string]$Gateway = "yarp",
    [string]$Frontend = "react"
)
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent

docker build -t "stackverse/backend-${Backend}:local" -f "$root/backends/$Backend/Dockerfile" $root
if ($LASTEXITCODE) { exit $LASTEXITCODE }

docker build -t "stackverse/gateway-${Gateway}:local" "$root/gateways/$Gateway"
if ($LASTEXITCODE) { exit $LASTEXITCODE }

docker build -t "stackverse/frontend-${Frontend}:local" -f "$root/frontends/$Frontend/Dockerfile" $root
exit $LASTEXITCODE

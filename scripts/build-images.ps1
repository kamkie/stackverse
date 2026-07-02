#!/usr/bin/env pwsh
# Build local Docker images for one backend + one gateway implementation.
#
#   ./scripts/build-images.ps1                     # spring-kotlin + yarp
#   ./scripts/build-images.ps1 -Backend go -Gateway spring-cloud-gateway
#
# Conventions (see backends/README.md, gateways/README.md):
#   - backend images build with the REPO ROOT as context (they bundle spec/messages)
#   - gateway images build with their own directory as context
param(
    [string]$Backend = "spring-kotlin",
    [string]$Gateway = "yarp"
)
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent

docker build -t "stackverse/backend-${Backend}:local" -f "$root/backends/$Backend/Dockerfile" $root
if ($LASTEXITCODE) { exit $LASTEXITCODE }

docker build -t "stackverse/gateway-${Gateway}:local" "$root/gateways/$Gateway"
exit $LASTEXITCODE

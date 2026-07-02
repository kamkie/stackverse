#!/usr/bin/env pwsh
# Run the full stack (infra + backend + gateway) with locally built images.
# Runs attached — Ctrl+C stops everything. See docs/RUNNING.md.
#
#   ./scripts/run-stack.ps1                  # spring-kotlin + yarp
#   ./scripts/run-stack.ps1 -Build           # docker build first
#   ./scripts/run-stack.ps1 -Observability   # + Grafana on :3000, OTLP export on
param(
    [string]$Backend = "spring-kotlin",
    [string]$Gateway = "yarp",
    [switch]$Build,
    [switch]$Observability
)
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent

if ($Build) {
    & "$PSScriptRoot/build-images.ps1" -Backend $Backend -Gateway $Gateway
    if ($LASTEXITCODE) { exit $LASTEXITCODE }
}

$env:BACKEND_IMAGE = "stackverse/backend-${Backend}:local"
$env:GATEWAY_IMAGE = "stackverse/gateway-${Gateway}:local"

$composeArgs = @("compose", "--profile", "app")
if ($Observability) {
    $composeArgs += @("--profile", "observability")
    $env:OTEL_SDK_DISABLED = "false"
}
$composeArgs += "up"

Set-Location $root
docker @composeArgs
exit $LASTEXITCODE

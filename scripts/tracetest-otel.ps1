<#
Trace-based observability tests against a composed stack with telemetry on.
Runs attached and stops started containers when the Tracetest runner exits.
#>
#Requires -Version 7
param(
    [string]$Backend = "spring-kotlin",
    [string]$Gateway = "yarp",
    [string]$Frontend = "react",
    [switch]$Build
)
$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$projectName = if ($env:STACKVERSE_TRACETEST_PROJECT) { $env:STACKVERSE_TRACETEST_PROJECT } else { "stackverse-tracetest" }

if ($Build) {
    & "$PSScriptRoot/build-images.ps1" -Backend $Backend -Gateway $Gateway -Frontend $Frontend
    if ($LASTEXITCODE) { exit $LASTEXITCODE }
}

$env:BACKEND_IMAGE = "stackverse/backend-${Backend}:local"
$env:GATEWAY_IMAGE = "stackverse/gateway-${Gateway}:local"
$env:FRONTEND_IMAGE = "stackverse/frontend-${Frontend}:local"
$env:OTEL_SDK_DISABLED = "false"

Set-Location $repoRoot
$composeArgs = @(
    "-p", $projectName,
    "-f", "compose.yaml",
    "-f", "testing/tracetest-otel/compose.yaml",
    "--profile", "app",
    "--profile", "observability",
    "--profile", "tracetest"
)

docker compose @composeArgs down --remove-orphans
if ($LASTEXITCODE) { exit $LASTEXITCODE }

$exitCode = 0
try {
    docker compose @composeArgs up --force-recreate --abort-on-container-exit --exit-code-from tracetest-run
    $exitCode = $LASTEXITCODE
}
finally {
    docker compose @composeArgs stop | Out-Null
}
exit $exitCode

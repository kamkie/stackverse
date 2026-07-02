<#
Contract conformance suite against a RUNNING backend (no gateway or frontend
needed — just the compose infra and one backend; BACKEND_URL / KEYCLOAK_URL
override the defaults http://localhost:8080 / http://localhost:8180).
Extra arguments are passed to `playwright test` (e.g. ./scripts/conformance.ps1 -g pagination).
#>
#Requires -Version 7
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location (Join-Path $repoRoot 'conformance')
yarn install --immutable
yarn playwright test @args
exit $LASTEXITCODE

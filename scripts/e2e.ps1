<#
Playwright end-to-end suite against a RUNNING stack (dev-stack or run-stack,
gateway on http://localhost:8000 unless STACKVERSE_URL says otherwise).
Extra arguments are passed to `playwright test` (e.g. ./scripts/e2e.ps1 --headed).
#>
#Requires -Version 7
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location (Join-Path $repoRoot 'e2e')
yarn install --immutable
yarn playwright install chromium
yarn playwright test @args
exit $LASTEXITCODE

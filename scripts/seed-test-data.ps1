<#
Seed a running Stackverse backend with repeatable local demo data.

Defaults:
  BACKEND_URL=http://localhost:8080
  KEYCLOAK_URL=http://localhost:8180

The script uses the dev-only stackverse-conformance Keycloak client and the
public backend API. It does not change the API contract or write backend-specific
database rows.
#>
#Requires -Version 7
[CmdletBinding()]
param(
    [string]$BackendUrl = $env:BACKEND_URL,
    [string]$KeycloakUrl = $env:KEYCLOAK_URL
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot

if ([string]::IsNullOrWhiteSpace($BackendUrl)) {
    $BackendUrl = 'http://localhost:8080'
}
if ([string]::IsNullOrWhiteSpace($KeycloakUrl)) {
    $KeycloakUrl = 'http://localhost:8180'
}

$nodeBin = $env:NODE_BIN
if ([string]::IsNullOrWhiteSpace($nodeBin)) {
    $nodeBin = 'node'
}

if (-not (Get-Command $nodeBin -ErrorAction SilentlyContinue)) {
    throw 'Node.js 18+ is required to run the seed helper.'
}

$nodeVersion = (& $nodeBin --version).Trim()
if ($LASTEXITCODE -ne 0) {
    throw 'Node.js 18+ is required to run the seed helper.'
}
$nodeMajor = [int]($nodeVersion.TrimStart('v').Split('.')[0])
if ($nodeMajor -lt 18) {
    throw "Node.js 18+ is required to run the seed helper; found $nodeVersion."
}

$env:BACKEND_URL = $BackendUrl
$env:KEYCLOAK_URL = $KeycloakUrl
& $nodeBin (Join-Path $repoRoot 'tools\seed-test-data.mjs')
exit $LASTEXITCODE

<#
Hurl API showcase against a RUNNING backend (no gateway or frontend needed).
BACKEND_URL / KEYCLOAK_URL override the defaults http://localhost:8080 /
http://localhost:8180. Extra arguments are passed to `hurl`.
#>
#Requires -Version 7
[CmdletBinding()]
param(
    [string]$BackendUrl = $env:BACKEND_URL,
    [string]$KeycloakUrl = $env:KEYCLOAK_URL,
    [string]$RunId = $env:HURL_RUN_ID,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$HurlArgs
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$suiteRoot = Join-Path $repoRoot 'testing/hurl-api'

if ([string]::IsNullOrWhiteSpace($BackendUrl)) {
    $BackendUrl = 'http://localhost:8080'
}
if ([string]::IsNullOrWhiteSpace($KeycloakUrl)) {
    $KeycloakUrl = 'http://localhost:8180'
}
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds().ToString('x')
    $suffix = (Get-Random -Maximum 1048576).ToString('x')
    $RunId = "hurl-$timestamp-$suffix"
}
if ($RunId -notmatch '^[a-z0-9-]+$') {
    throw "HURL_RUN_ID must contain only lowercase letters, digits, and hyphens because it is used in message keys; got '$RunId'."
}

$hurl = Get-Command hurl -ErrorAction SilentlyContinue
if (-not $hurl) {
    Write-Error 'Hurl is required to run testing/hurl-api. Install it from https://hurl.dev/docs/installation.html and rerun this helper.'
    exit 1
}

Set-Location $suiteRoot
& $hurl.Source --test `
    --variable "backend_url=$BackendUrl" `
    --variable "keycloak_url=$KeycloakUrl" `
    --variable "run_id=$RunId" `
    @HurlArgs `
    stackverse-showcase.hurl
exit $LASTEXITCODE

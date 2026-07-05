<#
OWASP ZAP baseline smoke scan against a RUNNING Stackverse gateway.
STACKVERSE_URL defaults to http://localhost:8000. Extra arguments are passed
to zap-baseline.py.
#>
#Requires -Version 7
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$suiteRoot = Join-Path $repoRoot "testing/zap-security"

function Get-EnvOrDefault {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Default
    )
    $value = [Environment]::GetEnvironmentVariable($Name)
    if ([string]::IsNullOrWhiteSpace($value)) {
        return $Default
    }
    return $value
}

function Test-Truthy {
    param([string]$Value)
    return $Value -match '^(1|true|yes|on)$'
}

function Convert-LocalhostForContainer {
    param([Parameter(Mandatory = $true)][string]$Url)
    try {
        $builder = [System.UriBuilder]::new($Url)
        if ($builder.Host -eq "localhost" -or $builder.Host -eq "127.0.0.1") {
            $builder.Host = "host.docker.internal"
            return $builder.Uri.AbsoluteUri
        }
    } catch {
        return $Url
    }
    return $Url
}

$stackverseUrl = Get-EnvOrDefault -Name "STACKVERSE_URL" -Default "http://localhost:8000"
$zapTargetUrl = [Environment]::GetEnvironmentVariable("ZAP_TARGET_URL")
if ([string]::IsNullOrWhiteSpace($zapTargetUrl)) {
    $zapTargetUrl = Convert-LocalhostForContainer -Url $stackverseUrl
}

$zapDockerImage = Get-EnvOrDefault -Name "ZAP_DOCKER_IMAGE" -Default "ghcr.io/zaproxy/zaproxy:stable"
$zapReportDir = Get-EnvOrDefault -Name "ZAP_REPORT_DIR" -Default (Join-Path $suiteRoot "reports")
$zapConfigFile = Get-EnvOrDefault -Name "ZAP_CONFIG_FILE" -Default (Join-Path $suiteRoot "zap-baseline.conf")
$zapSpiderMinutes = Get-EnvOrDefault -Name "ZAP_SPIDER_MINUTES" -Default "1"
$zapMaxMinutes = Get-EnvOrDefault -Name "ZAP_MAX_MINUTES" -Default "5"
$zapFailOnWarnings = Test-Truthy ([Environment]::GetEnvironmentVariable("ZAP_FAIL_ON_WARNINGS"))
$zapDockerNetwork = [Environment]::GetEnvironmentVariable("ZAP_DOCKER_NETWORK")

New-Item -ItemType Directory -Force -Path $zapReportDir | Out-Null

$dockerArgs = @(
    "run",
    "--rm",
    "--add-host",
    "host.docker.internal:host-gateway",
    "-v",
    "${zapReportDir}:/zap/wrk:rw",
    "-v",
    "${zapConfigFile}:/zap/config/zap-baseline.conf:ro"
)

if (-not [string]::IsNullOrWhiteSpace($zapDockerNetwork)) {
    $dockerArgs += @("--network", $zapDockerNetwork)
}

$zapArgs = @(
    "zap-baseline.py",
    "-t", $zapTargetUrl,
    "-m", $zapSpiderMinutes,
    "-T", $zapMaxMinutes,
    "-c", "/zap/config/zap-baseline.conf",
    "-r", "zap-baseline.html",
    "-w", "zap-baseline.md",
    "-J", "zap-baseline.json",
    "-s"
)

if (-not $zapFailOnWarnings) {
    $zapArgs += "-I"
}

$commandArgs = $dockerArgs + $zapDockerImage + $zapArgs + $args

Write-Host "Running OWASP ZAP baseline scan"
Write-Host "  STACKVERSE_URL: $stackverseUrl"
Write-Host "  ZAP target URL: $zapTargetUrl"
Write-Host "  reports:        $zapReportDir"
Write-Host "  image:          $zapDockerImage"

& docker @commandArgs
exit $LASTEXITCODE

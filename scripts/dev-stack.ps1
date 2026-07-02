<#
Full dev mode: Docker infra + each module (backend, gateway, frontend) as a dev
process in its own Windows Terminal tab. Every module's output is tee'd to
.logs/<module>.log at the repo root so humans watch the terminals and agents
read the files. See AGENTS.md, "Full dev mode".

Usage:
  ./scripts/dev-stack.ps1            # start infra, open the three tabs
  ./scripts/dev-stack.ps1 -Tab backend   # internal: run one module here (tee'd)
#>
#Requires -Version 7
[CmdletBinding()]
param(
    # internal: run a single module in the current terminal (used by the tabs)
    [ValidateSet('backend', 'gateway', 'frontend')]
    [string]$Tab
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$logDir = Join-Path $repoRoot '.logs'

if ($Tab) {
    New-Item -ItemType Directory -Force $logDir | Out-Null
    $log = Join-Path $logDir "$Tab.log"
    # native stderr must flow into the tee, not throw
    $ErrorActionPreference = 'Continue'
    # tee by hand: Tee-Object buffers ~kBs, but agents need the tail on disk live
    $writer = [System.IO.StreamWriter]::new($log, $false)
    $writer.AutoFlush = $true
    $tee = { process { $_; $writer.WriteLine($_) } }
    try {
        switch ($Tab) {
            'backend' {
                Set-Location (Join-Path $repoRoot 'backends\spring-kotlin')
                ./gradlew bootRun 2>&1 | & $tee
            }
            'gateway' {
                $env:FRONTEND_URL = 'http://localhost:5173'
                Set-Location (Join-Path $repoRoot 'gateways\yarp')
                dotnet run --project src/StackverseGateway 2>&1 | & $tee
            }
            'frontend' {
                $env:VITE_API_MOCK = 'false'
                Set-Location (Join-Path $repoRoot 'frontends\react')
                yarn dev 2>&1 | & $tee
            }
        }
    }
    finally { $writer.Close() }
    return
}

Set-Location $repoRoot
docker compose up -d

Write-Host 'Waiting for Keycloak to become healthy...'
$deadline = (Get-Date).AddSeconds(180)
do {
    $status = docker inspect --format '{{.State.Health.Status}}' stackverse-keycloak-1
    if ($status -eq 'healthy') { break }
    Start-Sleep -Seconds 5
} while ((Get-Date) -lt $deadline)
if ($status -ne 'healthy') { throw "Keycloak not healthy after 180s (status: $status)" }

# one wt invocation per tab — wt splits inline commands on ';', so each tab
# re-enters this script via -File instead (see AGENTS.md, "Windows Terminal pitfalls")
foreach ($module in 'backend', 'gateway', 'frontend') {
    wt -w stackverse new-tab --title $module --suppressApplicationTitle pwsh -NoExit -File $PSCommandPath -Tab $module
}
Write-Host "Tabs launched. App: http://localhost:8000 - logs: $logDir\<module>.log"

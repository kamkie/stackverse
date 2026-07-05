<#
k6 system smoke and light-load showcase against a RUNNING stack
(gateway on http://localhost:8000 unless STACKVERSE_URL says otherwise).
Extra arguments are passed to `k6 run`.
#>
#Requires -Version 7
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $K6Args
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$suiteRoot = Join-Path $repoRoot "testing/k6-system"
$k6 = if ($env:K6_BIN) { $env:K6_BIN } else { "k6" }

& $k6 version | Out-Host
if ($LASTEXITCODE) { exit $LASTEXITCODE }

if ($env:K6_SKIP_SMOKE -ne "true") {
    & $k6 run @K6Args (Join-Path $suiteRoot "smoke.js")
    if ($LASTEXITCODE) { exit $LASTEXITCODE }
}

& $k6 run @K6Args (Join-Path $suiteRoot "light-load.js")
exit $LASTEXITCODE

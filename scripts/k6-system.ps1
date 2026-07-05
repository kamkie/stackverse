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
$summaryDir = if ($env:K6_SUMMARY_DIR) { $env:K6_SUMMARY_DIR } else { "" }

function Invoke-K6Script {
    param(
        [string] $ScriptName,
        [string] $SummaryName
    )

    $argsForRun = @("run") + $K6Args
    if ($summaryDir) {
        New-Item -ItemType Directory -Force -Path $summaryDir | Out-Null
        $argsForRun += @("--summary-export", (Join-Path $summaryDir $SummaryName))
    }
    $argsForRun += (Join-Path $suiteRoot $ScriptName)

    & $k6 @argsForRun | Out-Host
    return [int]$LASTEXITCODE
}

& $k6 version | Out-Host
if ($LASTEXITCODE) { exit $LASTEXITCODE }

if ($env:K6_SKIP_SMOKE -ne "true") {
    $exitCode = Invoke-K6Script "smoke.js" "smoke-summary.json"
    if ($exitCode) { exit $exitCode }
}

$exitCode = Invoke-K6Script "light-load.js" "light-load-summary.json"
exit $exitCode

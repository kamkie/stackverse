<#
Per-variant code statistics for Stackverse. Thin wrapper around the single
implementation in tools/code-stats.mjs (mirrors scripts/code-stats.sh).

Files come from `git ls-files`; line counts and languages from `tokei`.
Requires Node.js 18+ and tokei on PATH.

Examples:
  ./scripts/code-stats.ps1
  ./scripts/code-stats.ps1 -Format markdown -Write docs/CODE-STATS.md
  ./scripts/code-stats.ps1 -Component backend -Component frontend
#>
#Requires -Version 7
[CmdletBinding()]
param(
    [ValidateSet('table', 'markdown', 'json')]
    [string]$Format,

    [ValidateSet('backend', 'gateway', 'frontend')]
    [string[]]$Component,

    [string]$Write,

    [switch]$Help
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot

$nodeBin = $env:NODE_BIN
if ([string]::IsNullOrWhiteSpace($nodeBin)) {
    $nodeBin = 'node'
}

if (-not (Get-Command $nodeBin -ErrorAction SilentlyContinue)) {
    throw 'Node.js 18+ is required to run the code-stats helper.'
}

$nodeVersionOutput = & $nodeBin --version 2>$null
if ($LASTEXITCODE -ne 0 -or $null -eq $nodeVersionOutput) {
    throw 'Node.js 18+ is required to run the code-stats helper.'
}
$nodeVersion = ($nodeVersionOutput | Select-Object -First 1).ToString().Trim()
$nodeMajorText = $nodeVersion.TrimStart('v').Split('.')[0]
$nodeMajor = 0
if (-not [int]::TryParse($nodeMajorText, [ref]$nodeMajor) -or $nodeMajor -lt 18) {
    throw "Node.js 18+ is required to run the code-stats helper; found $nodeVersion."
}

# Translate the PowerShell-native parameters into the helper's CLI flags.
$helperArgs = @()
if ($Help) { $helperArgs += '--help' }
if ($Format) { $helperArgs += @('--format', $Format) }
foreach ($c in $Component) { $helperArgs += @('--component', $c) }
if ($Write) { $helperArgs += @('--write', $Write) }

& $nodeBin (Join-Path $repoRoot 'tools\code-stats.mjs') @helperArgs
exit $LASTEXITCODE

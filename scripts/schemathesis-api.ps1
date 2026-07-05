<#
Schemathesis OpenAPI property tests against a RUNNING backend (no gateway or
frontend needed). BACKEND_URL / KEYCLOAK_URL override the defaults
http://localhost:8080 / http://localhost:8180. Extra arguments are passed to
`st run`.
#>
#Requires -Version 7
$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$suiteRoot = Join-Path $repoRoot "testing/schemathesis-api"
$venvRoot = Join-Path $suiteRoot ".venv-windows"
$python = if ($env:PYTHON_BIN) { $env:PYTHON_BIN } else { "python" }
$venvPython = Join-Path $venvRoot "Scripts/python.exe"

if (-not (Test-Path $venvPython)) {
    & $python -m venv $venvRoot
    if ($LASTEXITCODE) { exit $LASTEXITCODE }
    if (-not (Test-Path $venvPython)) {
        Write-Error "virtualenv was created but no Python executable was found under $venvRoot"
        exit 1
    }
}

& $venvPython -m pip install --disable-pip-version-check -r (Join-Path $suiteRoot "requirements.txt")
if ($LASTEXITCODE) { exit $LASTEXITCODE }

Set-Location $suiteRoot
& $venvPython -m stackverse_schemathesis @args
exit $LASTEXITCODE

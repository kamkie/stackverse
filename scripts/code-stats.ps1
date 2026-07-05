<#
.SYNOPSIS
  Per-variant code statistics for Stackverse.

.DESCRIPTION
  Counts lines of code for every implementation variant under backends/,
  gateways/ and frontends/, split into four roles so stacks compare on equal
  footing:

    app    implementation source + runtime/behavioural config
           (application.yml, nginx.conf, routes, *.sql migrations, server.xml, ...)
    tests  test source (files under test/ tests/ __tests__/ spec/, or named
           *.test.* *.spec.* *Test.* *Spec.* *_test.go test_*.py)
    infra  build, dependency, tooling and container config
           (build.gradle, pom.xml, *.csproj, Cargo.toml, go.mod, package.json,
           tsconfig*.json, root *.config.*, Dockerfile, gradle wrapper, ...)
    docs   prose (*.md, *.adoc, *.rst, LICENSE, ...)

  Counting engine is `tokei` (respects .gitignore, skips hidden files, so build
  output and vendored caches are already excluded). Classification is a
  documented heuristic (see Get-Bucket below); the rules are visible and
  adjustable. Raw LOC is a shape/verbosity signal, not a measure of effort or
  quality: the same contract in Rust and in Scala lands at very different totals.

  The App/Tests/Infra columns report tokei's `code` lines (standard LOC). The
  Docs column reports prose lines (code + comments): tokei models Markdown text
  as comments with zero code, so a code-only docs figure would always be 0. The
  csv output keeps the raw code/comments/blanks per bucket for full precision.

  Known limitation: Rust's inline #[cfg(test)] tests share a file with the code
  they test and cannot be separated by path, so they count as app for rust-axum.

  Keep this file in lock-step with scripts/code-stats.sh (same rules, same
  output).

.PARAMETER Format
  table (default, human console), markdown (docs page), or csv (machine).

.PARAMETER Layer
  Limit to one layer: backends | gateways | frontends.

.PARAMETER Out
  Write output to this file as UTF-8 (no BOM) instead of stdout.

.EXAMPLE
  ./scripts/code-stats.ps1
  ./scripts/code-stats.ps1 -Format markdown -Out docs/CODE-STATS.md
#>
[CmdletBinding()]
param(
  [ValidateSet('table', 'markdown', 'csv')]
  [string]$Format = 'table',

  [ValidateSet('backends', 'gateways', 'frontends')]
  [string]$Layer,

  [string]$Out
)

$ErrorActionPreference = 'Stop'

foreach ($tool in 'tokei') {
  if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
    Write-Error "code-stats: '$tool' not found on PATH (https://github.com/XAMPPRocky/tokei)"
    exit 1
  }
}

# Resolve the repo root from this script's location so it runs from anywhere.
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Test-AnyLike {
  param([string]$Value, [string[]]$Patterns)
  foreach ($p in $Patterns) { if ($Value -like $p) { return $true } }
  return $false
}

# Get-Bucket <relative-path> -> app | tests | infra | docs
# First match wins; order is docs -> infra -> tests -> app.
function Get-Bucket {
  param([string]$Rel)
  $base = ($Rel -split '/')[-1]
  $lrel = $Rel.ToLowerInvariant()
  $lbase = $base.ToLowerInvariant()

  # --- docs ---
  if (Test-AnyLike $lbase @('license', 'license.*', 'copying', 'notice',
      '*.md', '*.markdown', '*.mdx', '*.adoc', '*.asciidoc', '*.rst', '*.txt')) { return 'docs' }

  # --- infra (basename rules) ---
  if (Test-AnyLike $lbase @(
      'dockerfile', 'dockerfile.*', '*.dockerfile', '.dockerignore', 'docker-entrypoint.sh', 'entrypoint.sh',
      'docker-compose*.yml', 'docker-compose*.yaml', 'compose*.yml', 'compose*.yaml',
      'package.json', 'package-lock.json', 'yarn.lock', 'pnpm-lock.yaml', 'npm-shrinkwrap.json', '.npmrc', '.nvmrc', '.browserslistrc',
      'tsconfig*.json', 'jsconfig*.json',
      '.eslintrc*', 'eslint.config.*', '.prettierrc*', 'prettier.config.*', '.babelrc*', 'babel.config.*', 'angular.json', 'nest-cli.json', '.yarnrc*',
      'build.gradle', 'build.gradle.kts', 'settings.gradle', 'settings.gradle.kts', 'gradle.properties', 'gradlew', 'gradlew.bat', 'gradle-wrapper.*',
      'pom.xml', 'mvnw', 'mvnw.cmd', 'maven-wrapper.*',
      '*.sbt',
      '*.csproj', '*.fsproj', '*.vbproj', '*.sln', '*.slnx', '*.props', '*.targets', 'nuget.config', 'global.json', 'dotnet-tools.json',
      'cargo.toml', 'cargo.lock', 'rust-toolchain*', 'rustfmt.toml', 'clippy.toml',
      'go.mod', 'go.sum', '.golangci*',
      'pyproject.toml', 'poetry.lock', 'uv.lock', 'setup.py', 'setup.cfg', 'tox.ini', 'pipfile', 'pipfile.lock', '.python-version', 'requirements*.txt',
      'makefile', 'gnumakefile', '*.mk', '.gitignore', '.gitattributes', '.editorconfig', '.env', '.env.*'
    )) { return 'infra' }

  # Root-level tool configs only (vite.config.ts, vitest.config.ts, svelte.config.js,
  # proxy.conf.mjs). Scoped to the variant root so nested app code such as Angular's
  # src/app/app.config.ts stays classified as app.
  if (-not $Rel.Contains('/')) {
    if (Test-AnyLike $lbase @('*.config.ts', '*.config.js', '*.config.mjs', '*.config.cjs',
        '*.conf.ts', '*.conf.js', '*.conf.mjs', '*.conf.cjs')) { return 'infra' }
  }

  # --- infra (path rules) ---
  if (Test-AnyLike $lrel @('gradle/*', '.gradle/*', '.mvn/*', '.yarn/*', '.github/*', 'project/*')) { return 'infra' }

  # --- tests (path segment) ---
  if (Test-AnyLike "/$lrel" @('*/test/*', '*/tests/*', '*/__tests__/*', '*/spec/*', '*/specs/*')) { return 'tests' }

  # --- tests (basename, lower-case) ---
  if (Test-AnyLike $lbase @('*.test.ts', '*.test.tsx', '*.test.js', '*.test.jsx', '*.test.mjs', '*.test.cjs',
      '*.spec.ts', '*.spec.tsx', '*.spec.js', '*.spec.jsx', '*.spec.mjs', '*.spec.cjs',
      '*_test.go', 'test_*.py', '*_test.py')) { return 'tests' }
  # --- tests (basename, case-sensitive JVM) ---
  if (Test-AnyLike $base @('*Test.kt', '*Test.java', '*Test.scala', '*Test.groovy',
      '*Tests.kt', '*Tests.java', '*Tests.scala', '*Tests.groovy',
      '*Spec.kt', '*Spec.scala', '*Spec.groovy', '*IT.kt', '*IT.java', '*IT.scala')) { return 'tests' }

  return 'app'
}

# Aggregate one variant directory -> ordered hashtable of per-bucket [code,comments,blanks,files].
function Get-VariantStats {
  param([string]$Dir)
  $acc = @{
    app   = @(0, 0, 0, 0)
    tests = @(0, 0, 0, 0)
    infra = @(0, 0, 0, 0)
    docs  = @(0, 0, 0, 0)
  }
  $json = (& tokei $Dir --files --output json) | ConvertFrom-Json
  $prefix = "$Dir/"
  foreach ($prop in $json.PSObject.Properties) {
    if ($prop.Name -eq 'Total') { continue }
    $reports = $prop.Value.reports
    if (-not $reports) { continue }
    foreach ($r in $reports) {
      $rel = ($r.name -replace '\\', '/')
      if ($rel.StartsWith($prefix)) { $rel = $rel.Substring($prefix.Length) }
      $b = Get-Bucket $rel
      $acc[$b][0] += [int]$r.stats.code
      $acc[$b][1] += [int]$r.stats.comments
      $acc[$b][2] += [int]$r.stats.blanks
      $acc[$b][3] += 1
    }
  }
  return $acc
}

# Discover variants from the filesystem (O(1) in the script as variants are added).
$layers = 'backends', 'gateways', 'frontends'
$records = [System.Collections.Generic.List[object]]::new()
foreach ($lyr in $layers) {
  if ($Layer -and $lyr -ne $Layer) { continue }
  foreach ($d in (Get-ChildItem -Path $lyr -Directory | Sort-Object Name)) {
    $dir = "$lyr/$($d.Name)"
    $s = Get-VariantStats $dir
    $records.Add([pscustomobject]@{ layer = $lyr; variant = $d.Name; stats = $s })
  }
}

function Format-Ratio {
  param([int]$Tests, [int]$App)
  if (($App + $Tests) -le 0) { return '0.0' }
  # Force invariant culture so the decimal separator is '.' (matches the .sh flavor).
  return ([string]::Format([System.Globalization.CultureInfo]::InvariantCulture, '{0:F1}', (100.0 * $Tests / ($App + $Tests))))
}

function Write-Result {
  param([string]$Text)
  if ($Out) {
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText([System.IO.Path]::GetFullPath($Out), $Text, $utf8)
  }
  else {
    [Console]::Out.Write($Text)
  }
}

function Render-Table {
  $sb = [System.Text.StringBuilder]::new()
  $hdrFmt = '{0,-22} {1,8} {2,8} {3,8} {4,8} {5,8} {6,7} {7,7}'
  $hdr = $hdrFmt -f 'Variant', 'App', 'Tests', 'Infra', 'Docs', 'Total', 'Test%', 'Files'
  $sep = '-' * $hdr.Length
  $last = ''
  $la = 0; $lt = 0; $li = 0; $ld = 0; $lf = 0
  $ga = 0; $gt = 0; $gi = 0; $gd = 0; $gf = 0
  foreach ($rec in $records) {
    $s = $rec.stats
    $ac = $s.app[0]; $tc = $s.tests[0]; $ic = $s.infra[0]
    $dd = $s.docs[0] + $s.docs[1]
    $total = $ac + $tc + $ic + $dd
    $files = $s.app[3] + $s.tests[3] + $s.infra[3] + $s.docs[3]
    $ratio = Format-Ratio $tc $ac
    if ($rec.layer -ne $last) {
      if ($last -ne '') {
        [void]$sb.AppendLine(('{0,-22} {1,8} {2,8} {3,8} {4,8} {5,8} {6,7} {7,7}' -f '  ~ subtotal', $la, $lt, $li, $ld, ($la + $lt + $li + $ld), '', $lf))
        [void]$sb.AppendLine('')
        $la = 0; $lt = 0; $li = 0; $ld = 0; $lf = 0
      }
      [void]$sb.AppendLine($rec.layer)
      [void]$sb.AppendLine($hdr)
      [void]$sb.AppendLine($sep)
      $last = $rec.layer
    }
    [void]$sb.AppendLine(('{0,-22} {1,8} {2,8} {3,8} {4,8} {5,8} {6,6}% {7,7}' -f $rec.variant, $ac, $tc, $ic, $dd, $total, $ratio, $files))
    $la += $ac; $lt += $tc; $li += $ic; $ld += $dd; $lf += $files
    $ga += $ac; $gt += $tc; $gi += $ic; $gd += $dd; $gf += $files
  }
  [void]$sb.AppendLine(('{0,-22} {1,8} {2,8} {3,8} {4,8} {5,8} {6,7} {7,7}' -f '  ~ subtotal', $la, $lt, $li, $ld, ($la + $lt + $li + $ld), '', $lf))
  [void]$sb.AppendLine('')
  [void]$sb.AppendLine(('{0,-22} {1,8} {2,8} {3,8} {4,8} {5,8} {6,7} {7,7}' -f 'ALL VARIANTS', $ga, $gt, $gi, $gd, ($ga + $gt + $gi + $gd), '', $gf))
  Write-Result ($sb.ToString())
}

function Render-Csv {
  $sb = [System.Text.StringBuilder]::new()
  [void]$sb.AppendLine('layer,variant,app_code,app_comments,app_blanks,app_files,tests_code,tests_comments,tests_blanks,tests_files,infra_code,infra_comments,infra_blanks,infra_files,docs_code,docs_comments,docs_blanks,docs_files,total_lines,total_files')
  foreach ($rec in $records) {
    $s = $rec.stats
    $ac = $s.app[0]; $tc = $s.tests[0]; $ic = $s.infra[0]; $dc = $s.docs[0]; $dm = $s.docs[1]
    $totalLines = $ac + $tc + $ic + $dc + $dm
    $totalFiles = $s.app[3] + $s.tests[3] + $s.infra[3] + $s.docs[3]
    [void]$sb.AppendLine(($rec.layer, $rec.variant,
        $s.app[0], $s.app[1], $s.app[2], $s.app[3],
        $s.tests[0], $s.tests[1], $s.tests[2], $s.tests[3],
        $s.infra[0], $s.infra[1], $s.infra[2], $s.infra[3],
        $s.docs[0], $s.docs[1], $s.docs[2], $s.docs[3],
        $totalLines, $totalFiles) -join ',')
  }
  Write-Result ($sb.ToString())
}

function Render-Markdown {
  $sb = [System.Text.StringBuilder]::new()
  [void]$sb.AppendLine('# Code statistics per variant')
  [void]$sb.AppendLine('')
  [void]$sb.AppendLine('<!-- Generated by scripts/code-stats (the .sh / .ps1 pair, markdown format). Do not edit by hand; rerun the script. -->')
  [void]$sb.AppendLine('')
  [void]$sb.AppendLine('One product, the same contract, implemented across many stacks. Line counts')
  [void]$sb.AppendLine('come from [`tokei`](https://github.com/XAMPPRocky/tokei) and are split by role')
  [void]$sb.AppendLine('so stacks compare on equal footing:')
  [void]$sb.AppendLine('')
  [void]$sb.AppendLine('| Role | What it counts |')
  [void]$sb.AppendLine('|---|---|')
  [void]$sb.AppendLine('| **app** | Implementation source and runtime/behavioural config (`application.yml`, `nginx.conf`, `routes`, `*.sql` migrations, `server.xml`, ...) |')
  [void]$sb.AppendLine('| **tests** | Test source (under `test/ tests/ __tests__/ spec/`, or named `*.test.*`, `*.spec.*`, `*Test.*`, `*Spec.*`, `*_test.go`, `test_*.py`) |')
  [void]$sb.AppendLine('| **infra** | Build, dependency, tooling and container config (`build.gradle`, `pom.xml`, `*.csproj`, `Cargo.toml`, `go.mod`, `package.json`, `Dockerfile`, ...) |')
  [void]$sb.AppendLine('| **docs** | Prose (`*.md`, `LICENSE`, ...) |')
  [void]$sb.AppendLine('')
  [void]$sb.AppendLine('`tokei` respects `.gitignore` and skips hidden files, so build output and')
  [void]$sb.AppendLine('vendored caches are excluded. Raw LOC is a shape/verbosity signal, not a measure')
  [void]$sb.AppendLine('of effort or quality. Rust `#[cfg(test)]` tests live in the file they test and')
  [void]$sb.AppendLine('cannot be split by path, so they count as **app** for `rust-axum`.')
  [void]$sb.AppendLine('')
  [void]$sb.AppendLine('**App / Tests / Infra** are tokei `code` lines (standard LOC). **Docs** is prose')
  [void]$sb.AppendLine('lines (`code + comments`) — Markdown has no `code` lines, so a code-only figure')
  [void]$sb.AppendLine('would read 0. `Test%` is `tests / (app + tests)` code. Regenerate with')
  [void]$sb.AppendLine('`scripts/code-stats.sh --format markdown --out docs/CODE-STATS.md` or')
  [void]$sb.AppendLine('`scripts/code-stats.ps1 -Format markdown -Out docs/CODE-STATS.md`.')

  $last = ''
  $la = 0; $lt = 0; $li = 0; $ld = 0; $lf = 0
  foreach ($rec in $records) {
    $s = $rec.stats
    $ac = $s.app[0]; $tc = $s.tests[0]; $ic = $s.infra[0]
    $dd = $s.docs[0] + $s.docs[1]
    $total = $ac + $tc + $ic + $dd
    $files = $s.app[3] + $s.tests[3] + $s.infra[3] + $s.docs[3]
    $ratio = Format-Ratio $tc $ac
    if ($rec.layer -ne $last) {
      if ($last -ne '') {
        [void]$sb.AppendLine("| **subtotal** | $la | $lt | $li | $ld | $($la + $lt + $li + $ld) | | $lf |")
        $la = 0; $lt = 0; $li = 0; $ld = 0; $lf = 0
      }
      [void]$sb.AppendLine('')
      [void]$sb.AppendLine("## $($rec.layer)")
      [void]$sb.AppendLine('')
      [void]$sb.AppendLine('| Variant | App | Tests | Infra | Docs | Total | Test% | Files |')
      [void]$sb.AppendLine('|---|--:|--:|--:|--:|--:|--:|--:|')
      $last = $rec.layer
    }
    [void]$sb.AppendLine("| $($rec.variant) | $ac | $tc | $ic | $dd | $total | $ratio% | $files |")
    $la += $ac; $lt += $tc; $li += $ic; $ld += $dd; $lf += $files
  }
  [void]$sb.AppendLine("| **subtotal** | $la | $lt | $li | $ld | $($la + $lt + $li + $ld) | | $lf |")
  Write-Result ($sb.ToString())
}

switch ($Format) {
  'table' { Render-Table }
  'csv' { Render-Csv }
  'markdown' { Render-Markdown }
}

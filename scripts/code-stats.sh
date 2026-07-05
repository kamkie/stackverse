#!/usr/bin/env bash
# code-stats.sh — per-variant code statistics for Stackverse.
#
# Counts lines of code for every implementation variant under backends/,
# gateways/ and frontends/, split into four roles so stacks compare on equal
# footing:
#
#   app    implementation source + runtime/behavioral config
#          (application.yml, nginx.conf, routes, *.sql migrations, server.xml, ...)
#   tests  test source (files under test/ tests/ __tests__/ spec/, or named
#          *.test.* *.spec.* *Test.* *Spec.* *_test.go test_*.py)
#   infra  build, dependency, tooling and container config
#          (build.gradle, pom.xml, *.csproj, Cargo.toml, go.mod, package.json,
#          tsconfig*.json, root *.config.*, Dockerfile, gradle wrapper, ...)
#   docs   prose (*.md, *.adoc, *.rst, LICENSE, ...)
#
# Counting engine is `tokei` (respects .gitignore, skips hidden files, so build
# output and vendored caches are already excluded). Classification is a
# documented heuristic — see classify() below; the rules are visible and
# adjustable. Raw LOC is a shape/verbosity signal, not a measure of effort or
# quality: the same contract in Rust and in Scala lands at very different totals.
#
# The App/Tests/Infra columns report tokei's `code` lines (standard LOC). The
# Docs column reports prose lines (code + comments): tokei models Markdown text
# as comments with zero code, so a code-only docs figure would always be 0. The
# csv output keeps the raw code/comments/blanks per bucket for full precision.
#
# Known limitation: Rust's inline #[cfg(test)] tests share a file with the code
# they test and cannot be separated by path, so they count as app for rust-axum.
#
# Keep this file in lock-step with scripts/code-stats.ps1 (same rules, same
# output). Test the shell flavor through Git Bash or WSL — do not just read it.
#
# Usage:
#   ./scripts/code-stats.sh [--format table|markdown|csv] [--layer LAYER] [--out FILE]
#
#   --format   table (default, human console), markdown (docs page), csv (machine)
#   --layer    limit to one layer: backends | gateways | frontends
#   --out      write output to FILE as UTF-8 instead of stdout
set -euo pipefail

FORMAT=table
OUT=
LAYER_FILTER=

show_help() {
  cat <<'EOF'
code-stats.sh — per-variant code statistics for Stackverse.

Counts lines of code for every implementation variant under backends/, gateways/
and frontends/, split into four roles (app / tests / infra / docs) with tokei.

Usage:
  ./scripts/code-stats.sh [--format table|markdown|csv] [--layer LAYER] [--out FILE]

  --format   table (default, human console), markdown (docs page), csv (machine)
  --layer    limit to one layer: backends | gateways | frontends
  --out      write output to FILE as UTF-8 instead of stdout
  -h,--help  show this help

App/Tests/Infra are tokei 'code' lines; Docs is prose lines (code+comments).
See the header of this file for the full classification taxonomy.
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --format) FORMAT="${2:-}"; shift 2;;
    --format=*) FORMAT="${1#*=}"; shift;;
    --layer) LAYER_FILTER="${2:-}"; shift 2;;
    --layer=*) LAYER_FILTER="${1#*=}"; shift;;
    --out) OUT="${2:-}"; shift 2;;
    --out=*) OUT="${1#*=}"; shift;;
    -h|--help) show_help; exit 0;;
    *) echo "code-stats: unknown argument: $1" >&2; exit 2;;
  esac
done

case "$FORMAT" in
  table|markdown|csv) ;;
  *) echo "code-stats: --format must be table, markdown or csv (got '$FORMAT')" >&2; exit 2;;
esac

case "$LAYER_FILTER" in
  ''|backends|gateways|frontends) ;;
  *) echo "code-stats: --layer must be backends, gateways or frontends (got '$LAYER_FILTER')" >&2; exit 2;;
esac

command -v tokei >/dev/null 2>&1 || { echo "code-stats: 'tokei' not found on PATH (https://github.com/XAMPPRocky/tokei)" >&2; exit 1; }
command -v jq    >/dev/null 2>&1 || { echo "code-stats: 'jq' not found on PATH" >&2; exit 1; }

# Resolve the repo root from this script's location so it runs from anywhere.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# classify <relative-path> -> app | tests | infra | docs
# First match wins; order is docs -> infra -> tests -> app.
classify() {
  local rel="$1"
  local base="${rel##*/}"
  local lower_rel="${rel,,}"
  local lower_base="${base,,}"

  # --- docs ---
  case "$lower_base" in
    license|license.*|copying|notice) echo docs; return;;
    *.md|*.markdown|*.mdx|*.adoc|*.asciidoc|*.rst|*.txt) echo docs; return;;
  esac

  # --- infra (basename rules) ---
  case "$lower_base" in
    dockerfile|dockerfile.*|*.dockerfile|.dockerignore|docker-entrypoint.sh|entrypoint.sh) echo infra; return;;
    docker-compose*.yml|docker-compose*.yaml|compose*.yml|compose*.yaml) echo infra; return;;
    package.json|package-lock.json|yarn.lock|pnpm-lock.yaml|npm-shrinkwrap.json|.npmrc|.nvmrc|.browserslistrc) echo infra; return;;
    tsconfig*.json|jsconfig*.json) echo infra; return;;
    .eslintrc*|eslint.config.*|.prettierrc*|prettier.config.*|.babelrc*|babel.config.*|angular.json|nest-cli.json|.yarnrc*) echo infra; return;;
    build.gradle|build.gradle.kts|settings.gradle|settings.gradle.kts|gradle.properties|gradlew|gradlew.bat|gradle-wrapper.*) echo infra; return;;
    pom.xml|mvnw|mvnw.cmd|maven-wrapper.*) echo infra; return;;
    *.sbt) echo infra; return;;
    *.csproj|*.fsproj|*.vbproj|*.sln|*.slnx|*.props|*.targets|nuget.config|global.json|dotnet-tools.json) echo infra; return;;
    cargo.toml|cargo.lock|rust-toolchain*|rustfmt.toml|clippy.toml) echo infra; return;;
    go.mod|go.sum|.golangci*) echo infra; return;;
    pyproject.toml|poetry.lock|uv.lock|setup.py|setup.cfg|tox.ini|pipfile|pipfile.lock|.python-version|requirements*.txt) echo infra; return;;
    makefile|gnumakefile|*.mk|.gitignore|.gitattributes|.editorconfig|.env|.env.*) echo infra; return;;
  esac
  # Root-level tool configs only (vite.config.ts, vitest.config.ts, svelte.config.js,
  # proxy.conf.mjs). Scoped to the variant root so nested app code such as Angular's
  # src/app/app.config.ts stays classified as app.
  case "$rel" in
    */*) ;; # nested -> not a root tool config
    *)
      case "$lower_base" in
        *.config.ts|*.config.mts|*.config.cts|*.config.js|*.config.mjs|*.config.cjs|*.conf.ts|*.conf.mts|*.conf.cts|*.conf.js|*.conf.mjs|*.conf.cjs) echo infra; return;;
      esac
      ;;
  esac
  # --- infra (path rules) ---
  case "$lower_rel" in
    gradle/*|.gradle/*|.mvn/*|.yarn/*|.github/*|project/*) echo infra; return;;
  esac

  # --- tests (path segment) ---
  case "/$lower_rel" in
    */test/*|*/tests/*|*/__tests__/*|*/spec/*|*/specs/*) echo tests; return;;
  esac
  # --- tests (basename) ---
  case "$lower_base" in
    *.test.ts|*.test.tsx|*.test.js|*.test.jsx|*.test.mjs|*.test.cjs|*.test.svelte|*.test.vue) echo tests; return;;
    *.spec.ts|*.spec.tsx|*.spec.js|*.spec.jsx|*.spec.mjs|*.spec.cjs|*.spec.svelte|*.spec.vue) echo tests; return;;
    *_test.go|test_*.py|*_test.py) echo tests; return;;
  esac
  case "$base" in
    *Test.kt|*Test.java|*Test.scala|*Test.groovy) echo tests; return;;
    *Tests.kt|*Tests.java|*Tests.scala|*Tests.groovy) echo tests; return;;
    *Spec.kt|*Spec.scala|*Spec.groovy) echo tests; return;;
    *IT.kt|*IT.java|*IT.scala) echo tests; return;;
  esac

  echo app
}

# aggregate <dir> -> "app_c app_m app_k app_f tests_c ... docs_f" (16 ints, space-sep)
# jq normalises Windows backslashes to '/' and strips the "<layer>/<variant>/"
# prefix so classify() always sees a clean forward-slash path relative to the
# variant root.
aggregate_variant() {
  local dir="$1"
  tokei "$dir" --files --output json \
    | jq -r --arg pre "$dir/" 'to_entries[] | select(.key != "Total") | .value.reports[]?
             | ((.name | gsub("\\\\"; "/")) as $p
                | (if ($p | startswith($pre)) then $p[($pre|length):] else $p end)) as $rel
             | [$rel, (.stats.code|tostring), (.stats.comments|tostring), (.stats.blanks|tostring)] | @tsv' \
    | { while IFS=$'\t' read -r rel code com bl; do
          [ -n "$rel" ] || continue
          printf '%s\t%s\t%s\t%s\n' "$(classify "$rel")" "$code" "$com" "$bl"
        done; } \
    | awk -F'\t' '
        { c[$1]+=$2; m[$1]+=$3; k[$1]+=$4; f[$1]+=1 }
        END {
          n=split("app tests infra docs", B, " ")
          out=""
          for (i=1; i<=n; i++) { b=B[i]; out=out (c[b]+0)" "(m[b]+0)" "(k[b]+0)" "(f[b]+0)" " }
          print out
        }'
}

# Discover variants from the filesystem (O(1) in the script as variants are added).
LAYERS="backends gateways frontends"
RECORDS=()   # each: layer<TAB>variant<TAB>16 space-separated ints
for layer in $LAYERS; do
  [ -z "$LAYER_FILTER" ] || [ "$layer" = "$LAYER_FILTER" ] || continue
  for dir in "$layer"/*/; do
    [ -d "$dir" ] || continue
    dir="${dir%/}"
    variant="${dir##*/}"
    agg="$(aggregate_variant "$dir")"
    RECORDS+=("$layer	$variant	$agg")
  done
done

# --- field helpers (record fields, 0-based within the 16-int block) ---
# app:   c0 m1 k2 f3     tests: c4 m5 k6 f7
# infra: c8 m9 k10 f11   docs:  c12 m13 k14 f15
field() { awk -v i="$2" '{print $i}' <<< "$1"; }  # 1-based on the 16-int block

emit() {  # write $1 to OUT (utf-8) or stdout
  if [ -n "$OUT" ]; then printf '%s' "$1" > "$OUT"; else printf '%s' "$1"; fi
}

render_table() {
  local buf="" last_layer="" la=0 lt=0 li=0 ld=0 lf=0 ga=0 gt=0 gi=0 gd=0 gf=0
  local hdr sep
  hdr=$(printf '%-22s %8s %8s %8s %8s %8s %7s %7s' Variant App Tests Infra Docs Total "Test%" Files)
  sep=$(printf '%.0s-' $(seq 1 ${#hdr}))
  for rec in "${RECORDS[@]}"; do
    local layer variant nums
    layer="${rec%%	*}"; rec="${rec#*	}"
    variant="${rec%%	*}"; nums="${rec#*	}"
    read -r ac am ak af tc tm tk tf ic im ik if_ dc dm dk df <<< "$nums"
    local dd=$((dc+dm))                 # docs measured as prose lines (markdown code is ~0)
    local total=$((ac+tc+ic+dd))
    local ratio="0.0"
    if [ $((ac+tc)) -gt 0 ]; then ratio=$(LC_ALL=C awk -v t="$tc" -v a="$ac" 'BEGIN{printf "%.1f", 100*t/(a+t)}'); fi
    local files=$((af+tf+if_+df))
    if [ "$layer" != "$last_layer" ]; then
      if [ -n "$last_layer" ]; then
        buf+=$(printf '%-22s %8d %8d %8d %8d %8d %7s %7d\n' "  ~ subtotal" "$la" "$lt" "$li" "$ld" $((la+lt+li+ld)) "" "$lf")$'\n\n'
        la=0; lt=0; li=0; ld=0; lf=0
      fi
      buf+="$(printf '%s\n%s\n%s\n' "$layer" "$hdr" "$sep")"$'\n'
      last_layer="$layer"
    fi
    buf+="$(printf '%-22s %8d %8d %8d %8d %8d %6s%% %7d' "$variant" "$ac" "$tc" "$ic" "$dd" "$total" "$ratio" "$files")"$'\n'
    la=$((la+ac)); lt=$((lt+tc)); li=$((li+ic)); ld=$((ld+dd)); lf=$((lf+files))
    ga=$((ga+ac)); gt=$((gt+tc)); gi=$((gi+ic)); gd=$((gd+dd)); gf=$((gf+files))
  done
  buf+=$(printf '%-22s %8d %8d %8d %8d %8d %7s %7d\n' "  ~ subtotal" "$la" "$lt" "$li" "$ld" $((la+lt+li+ld)) "" "$lf")$'\n\n'
  buf+="$(printf '%-22s %8d %8d %8d %8d %8d %7s %7d' "ALL VARIANTS" "$ga" "$gt" "$gi" "$gd" $((ga+gt+gi+gd)) "" "$gf")"$'\n'
  emit "$buf"
}

render_csv() {
  local buf="layer,variant,app_code,app_comments,app_blanks,app_files,tests_code,tests_comments,tests_blanks,tests_files,infra_code,infra_comments,infra_blanks,infra_files,docs_code,docs_comments,docs_blanks,docs_files,total_lines,total_files"$'\n'
  for rec in "${RECORDS[@]}"; do
    local layer variant nums
    layer="${rec%%	*}"; rec="${rec#*	}"
    variant="${rec%%	*}"; nums="${rec#*	}"
    read -r ac am ak af tc tm tk tf ic im ik if_ dc dm dk df <<< "$nums"
    buf+="$layer,$variant,$ac,$am,$ak,$af,$tc,$tm,$tk,$tf,$ic,$im,$ik,$if_,$dc,$dm,$dk,$df,$((ac+tc+ic+dc+dm)),$((af+tf+if_+df))"$'\n'
  done
  emit "$buf"
}

render_markdown() {
  local buf last_layer="" la=0 lt=0 li=0 ld=0 lf=0
  buf=$'# Code statistics per variant\n\n'
  buf+=$'<!-- Generated by scripts/code-stats (the .sh / .ps1 pair, markdown format). Do not edit by hand; rerun the script. -->\n\n'
  buf+=$'One product, the same contract, implemented across many stacks. Line counts\n'
  buf+=$'come from [`tokei`](https://github.com/XAMPPRocky/tokei) and are split by role\n'
  buf+=$'so stacks compare on equal footing:\n\n'
  buf+=$'| Role | What it counts |\n|---|---|\n'
  buf+=$'| **app** | Implementation source and runtime/behavioural config (`application.yml`, `nginx.conf`, `routes`, `*.sql` migrations, `server.xml`, ...) |\n'
  buf+=$'| **tests** | Test source (under `test/ tests/ __tests__/ spec/`, or named `*.test.*`, `*.spec.*`, `*Test.*`, `*Spec.*`, `*_test.go`, `test_*.py`) |\n'
  buf+=$'| **infra** | Build, dependency, tooling and container config (`build.gradle`, `pom.xml`, `*.csproj`, `Cargo.toml`, `go.mod`, `package.json`, `Dockerfile`, ...) |\n'
  buf+=$'| **docs** | Prose (`*.md`, `LICENSE`, ...) |\n\n'
  buf+=$'`tokei` respects `.gitignore` and skips hidden files, so build output and\n'
  buf+=$'vendored caches are excluded. Raw LOC is a shape/verbosity signal, not a measure\n'
  buf+=$'of effort or quality. Rust `#[cfg(test)]` tests live in the file they test and\n'
  buf+=$'cannot be split by path, so they count as **app** for `rust-axum`.\n\n'
  buf+=$'**App / Tests / Infra** are tokei `code` lines (standard LOC). **Docs** is prose\n'
  buf+=$'lines (`code + comments`) — Markdown has no `code` lines, so a code-only figure\n'
  buf+=$'would read 0. `Test%` is `tests / (app + tests)` code. Regenerate with\n'
  buf+=$'`scripts/code-stats.sh --format markdown --out docs/CODE-STATS.md` or\n'
  buf+=$'`scripts/code-stats.ps1 -Format markdown -Out docs/CODE-STATS.md`.\n'

  for rec in "${RECORDS[@]}"; do
    local layer variant nums
    layer="${rec%%	*}"; rec="${rec#*	}"
    variant="${rec%%	*}"; nums="${rec#*	}"
    read -r ac am ak af tc tm tk tf ic im ik if_ dc dm dk df <<< "$nums"
    local dd=$((dc+dm)) total files ratio="0.0"
    total=$((ac+tc+ic+dd)); files=$((af+tf+if_+df))
    if [ $((ac+tc)) -gt 0 ]; then ratio=$(LC_ALL=C awk -v t="$tc" -v a="$ac" 'BEGIN{printf "%.1f", 100*t/(a+t)}'); fi
    if [ "$layer" != "$last_layer" ]; then
      if [ -n "$last_layer" ]; then
        buf+="| **subtotal** | $la | $lt | $li | $ld | $((la+lt+li+ld)) | | $lf |"$'\n'
        la=0; lt=0; li=0; ld=0; lf=0
      fi
      buf+=$'\n## '"$layer"$'\n\n'
      buf+=$'| Variant | App | Tests | Infra | Docs | Total | Test% | Files |\n'
      buf+=$'|---|--:|--:|--:|--:|--:|--:|--:|\n'
      last_layer="$layer"
    fi
    buf+="| $variant | $ac | $tc | $ic | $dd | $total | ${ratio}% | $files |"$'\n'
    la=$((la+ac)); lt=$((lt+tc)); li=$((li+ic)); ld=$((ld+dd)); lf=$((lf+files))
  done
  buf+="| **subtotal** | $la | $lt | $li | $ld | $((la+lt+li+ld)) | | $lf |"$'\n'
  emit "$buf"
}

case "$FORMAT" in
  table)    render_table;;
  csv)      render_csv;;
  markdown) render_markdown;;
esac

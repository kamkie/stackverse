#!/usr/bin/env bash
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
gate="$root/.github/scripts/codeql-gate.sh"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

expect_classification() {
  expected="$1"
  shift
  printf '%s\n' "$@" > "$tmp/changed-files"
  actual=$($gate classify "$tmp/changed-files")
  [ "$actual" = "$expected" ] || {
    echo "expected classification $expected, got $actual for: $*" >&2
    exit 1
  }
}

expect_classification false README.md docs/INTENT.md .github/CODEOWNERS
expect_classification true README.md .github/workflows/ci.yml
expect_classification true docs/guide.txt

: > "$tmp/empty"
if $gate classify "$tmp/empty" >/dev/null 2>&1; then
  echo "empty classification input must fail closed" >&2
  exit 1
fi

cat > "$tmp/alerts.json" <<'EOF'
[
  {"number":1,"rule":{"security_severity_level":"critical","severity":"warning"}},
  {"number":2,"rule":{"security_severity_level":"high","severity":"warning"}},
  {"number":3,"rule":{"security_severity_level":"medium","severity":"error"}},
  {"number":4,"rule":{"security_severity_level":null,"severity":"error"}},
  {"number":5,"rule":{"security_severity_level":"none","severity":"error"}},
  {"number":6,"rule":{"security_severity_level":null,"severity":"warning"}}
]
EOF
actual=$($gate blocking-alerts < "$tmp/alerts.json" | jq -c 'map(.number)')
[ "$actual" = '[1,2,4,5]' ] || {
  echo "unexpected blocking alert selection: $actual" >&2
  exit 1
}

echo "codeql gate tests passed"

#!/usr/bin/env sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
helper="$script_dir/wait-for-compose-service.sh"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

cat >"$tmp/curl" <<'EOF'
#!/usr/bin/env sh
count=0
if [ -f "$STATE_DIR/curl-count" ]; then count=$(cat "$STATE_DIR/curl-count"); fi
count=$((count + 1))
echo "$count" >"$STATE_DIR/curl-count"
case "$MODE" in
  success) exit 0 ;;
  retry-success) [ "$count" -gt 2 ] ;;
  retry-failure) exit 1 ;;
  recreate-failure) exit 1 ;;
  *) exit 2 ;;
esac
EOF

cat >"$tmp/docker" <<'EOF'
#!/usr/bin/env sh
echo "$*" >>"$STATE_DIR/docker-calls"
if [ "$1 $2 $3" = "compose ps -aq" ]; then echo backend-container; fi
if [ "$MODE" = "recreate-failure" ] && [ "$1 $2" = "compose up" ]; then exit 1; fi
exit 0
EOF

cat >"$tmp/sleep" <<'EOF'
#!/usr/bin/env sh
exit 0
EOF

chmod +x "$tmp/curl" "$tmp/docker" "$tmp/sleep" "$helper"

run_case() {
  mode=$1
  expected=$2
  state="$tmp/$mode"
  mkdir -p "$state"
  set +e
  MODE=$mode STATE_DIR=$state CURL_BIN="$tmp/curl" DOCKER_BIN="$tmp/docker" \
    SLEEP_BIN="$tmp/sleep" "$helper" backend http://localhost:8080/readyz 2 0 \
    >"$state/output" 2>&1
  actual=$?
  set -e
  if [ "$actual" -ne "$expected" ]; then
    cat "$state/output" >&2
    echo "$mode: expected exit $expected, got $actual" >&2
    exit 1
  fi
}

run_case success 0
[ ! -e "$tmp/success/docker-calls" ]

run_case retry-success 0
grep -q 'compose up -d --force-recreate --no-deps backend' "$tmp/retry-success/docker-calls"
[ "$(grep -c 'compose logs --no-color backend' "$tmp/retry-success/docker-calls")" -eq 1 ]

run_case retry-failure 1
grep -q 'compose up -d --force-recreate --no-deps backend' "$tmp/retry-failure/docker-calls"
[ "$(grep -c 'compose logs --no-color backend' "$tmp/retry-failure/docker-calls")" -eq 2 ]

run_case recreate-failure 1
grep -q 'could not be recreated' "$tmp/recreate-failure/output"
[ "$(grep -c 'compose logs --no-color backend' "$tmp/recreate-failure/docker-calls")" -eq 2 ]

echo "wait-for-compose-service tests passed"

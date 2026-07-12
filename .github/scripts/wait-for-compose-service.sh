#!/usr/bin/env sh
set -eu

service=${1:?service name is required}
url=${2:?readiness URL is required}
attempts=${3:-60}
delay=${4:-2}

curl_bin=${CURL_BIN:-curl}
docker_bin=${DOCKER_BIN:-docker}
sleep_bin=${SLEEP_BIN:-sleep}

wait_until_ready() {
  attempt=1
  while [ "$attempt" -le "$attempts" ]; do
    if "$curl_bin" -fsS "$url" >/dev/null 2>&1; then return 0; fi
    if [ "$attempt" -lt "$attempts" ]; then "$sleep_bin" "$delay"; fi
    attempt=$((attempt + 1))
  done
  return 1
}

dump_diagnostics() {
  echo "::group::Compose service diagnostics ($service)"
  "$docker_bin" compose ps -a || true
  container_id=$("$docker_bin" compose ps -aq "$service" 2>/dev/null || true)
  if [ -n "$container_id" ]; then
    echo "Container state:"
    "$docker_bin" inspect --format '{{json .State}}' "$container_id" || true
  else
    echo "No container id found for service $service."
  fi
  echo "Service logs:"
  "$docker_bin" compose logs --no-color "$service" || true
  echo "::endgroup::"
}

if wait_until_ready; then exit 0; fi

echo "Service $service did not become ready; collecting diagnostics and recreating it once." >&2
dump_diagnostics
"$docker_bin" compose up -d --force-recreate --no-deps "$service"

if wait_until_ready; then
  echo "Service $service became ready after one recreation."
  exit 0
fi

echo "Service $service did not become ready after one recreation." >&2
dump_diagnostics
exit 1

# k6 System Smoke And Light Load

This showcase suite uses k6 to exercise a running Stackverse stack through the
gateway. It is a system smoke and regression signal, not a benchmark and not a
replacement for the canonical `conformance/` and `e2e/` gates.

## Coverage

The suite demonstrates:

- anonymous public feed and message-bundle traffic through `STACKVERSE_URL`
- real gateway-to-Keycloak login using the authorization-code flow
- CSRF-protected bookmark setup and cleanup through the gateway API
- authenticated user reads for identity, bookmarks, tags, and one fixture bookmark
- moderator read access in the one-shot smoke script
- k6 thresholds for checks, unexpected status codes, unexpected 5xx responses,
  and bounded p95 latency under a deliberately small local load

The light-load phase mutates state only in `setup()` and `teardown()`. The steady
load itself is read-only. Created bookmark titles and tags are prefixed with
`k6-smoke` or `k6-load`, so interrupted runs are identifiable and repeatable.

## Local Run

Start a real Stackverse stack first, using dev mode or containers:

```sh
./scripts/run-stack.sh
```

```powershell
./scripts/run-stack.ps1
```

Install k6 locally, then run the full suite from the repo root:

```sh
./scripts/k6-system.sh
```

```powershell
./scripts/k6-system.ps1
```

The wrapper runs `smoke.js` first and then `light-load.js`. Extra arguments are
passed to each `k6 run` invocation:

```sh
./scripts/k6-system.sh --summary-export testing/k6-system/artifacts/k6-summary.json
```

Run individual scripts from this directory when iterating:

```sh
k6 run smoke.js
k6 run light-load.js
```

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `STACKVERSE_URL` | `http://localhost:8000` | Gateway URL for the running stack |
| `K6_DURATION` | `30s` | Duration for each light-load scenario |
| `K6_PUBLIC_VUS` | `1` | Anonymous public-feed virtual users |
| `K6_AUTH_VUS` | `1` | Authenticated user-read virtual users |
| `K6_P95_MS` | `1500` | p95 threshold for tagged smoke or steady requests |
| `K6_BIN` | `k6` | k6 executable used by the root helper scripts |
| `K6_SKIP_SMOKE` | `false` | Set to `true` to run only `light-load.js` through the helper |

Example:

```sh
STACKVERSE_URL=http://localhost:8000 \
K6_DURATION=45s \
K6_PUBLIC_VUS=1 \
K6_AUTH_VUS=1 \
./scripts/k6-system.sh
```

```powershell
$env:K6_DURATION = "45s"
$env:K6_PUBLIC_VUS = "1"
$env:K6_AUTH_VUS = "1"
./scripts/k6-system.ps1
```

## Default Bounds

The defaults are intentionally light:

- one anonymous public-feed VU
- one authenticated user-read VU
- 30 seconds per light-load scenario
- `checks` pass rate at least 99%
- zero unexpected `5xx` responses
- fewer than 1% unexpected statuses
- p95 below `K6_P95_MS` for the smoke or steady traffic tag

These thresholds are meant to catch broken routing, sessions, auth, obvious
server errors, and local-regression latency spikes. They are not capacity
targets and must not be used to rank Stackverse implementations.

## Authentication Model

The helpers drive the same browser-visible flow the app uses:

1. `GET /auth/login` at the gateway.
2. Fetch the Keycloak login form from the redirect target.
3. Submit the dev credential pair (`demo` / `demo`, `moderator` / `moderator`).
4. Replay the callback path to the configured gateway.
5. Use the gateway session cookie and echo `XSRF-TOKEN` as `X-XSRF-TOKEN` for
   state-changing `/api/**` calls.

The suite never reads, injects, or stores bearer tokens.

## CI

`.github/workflows/test-k6-system.yml` is manual (`workflow_dispatch`) so the
suite stays optional and non-blocking. It builds the reference
`spring-kotlin + yarp + react` stack, waits for it at `STACKVERSE_URL`, runs the
root helper, and uploads the k6 summary artifact.

Promote this suite to a required gate only with an explicit docs and CI change
that explains why k6 now defines correctness alongside the canonical gates.

## Limitations

The suite samples representative system traffic; it does not cover every
contract rule. Semantics still belong to `conformance/`, and browser-screen
coverage still belongs to `e2e/`. Local machine load, Docker cold starts, and CI
neighbor noise can all move the latency numbers, which is why the default
threshold is modest and documented instead of presented as a benchmark result.

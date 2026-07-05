# Schemathesis API Property Tests

This showcase suite uses Schemathesis to generate OpenAPI-driven property
tests from `spec/openapi.yaml` and run them against a live backend API. It is
edge-case and schema-conformance testing, not a replacement for the semantic
backend gate in `conformance/`.

## Target

The suite talks directly to a backend:

```text
BACKEND_URL=http://localhost:8080
KEYCLOAK_URL=http://localhost:8180
```

It uses the dev realm's `stackverse-conformance` password-grant client to get
a bearer token for `admin` by default. That token exercises authenticated,
moderator, and admin endpoints without involving the gateway or browser
session model. Use `SCHEMATHESIS_AUTH_ROLE=demo`, `moderator`, `mentor`,
`admin`, or `none` to change that behavior.

## Local Run

Prerequisites are Python with `venv` support and a running compose infra plus
any backend implementation. In Git Bash on Windows, set `PYTHON_BIN=python` if
`python3` is not on `PATH`; in Debian/Ubuntu WSL, install `python3-venv` if
venv creation reports that `ensurepip` is unavailable.

Then run from the repo root:

```sh
./scripts/schemathesis-api.sh
```

```powershell
./scripts/schemathesis-api.ps1
```

The helper creates a platform-specific ignored virtualenv under
`testing/schemathesis-api/.venv-*`, installs the pinned Python dependencies,
fetches the token, and invokes Schemathesis against `spec/openapi.yaml`.

Useful overrides:

```sh
BACKEND_URL=http://localhost:8080 \
KEYCLOAK_URL=http://localhost:8180 \
SCHEMATHESIS_MAX_EXAMPLES=50 \
./scripts/schemathesis-api.sh --checks not_a_server_error,status_code_conformance
```

```powershell
$env:SCHEMATHESIS_MAX_EXAMPLES = "50"
./scripts/schemathesis-api.ps1 --checks not_a_server_error,status_code_conformance
```

Extra arguments after the helper command are passed to `st run`.

## Default Bounds

The default profile is intentionally bounded:

- positive fuzzing only (`--phases=fuzzing --mode=positive`)
- one worker
- `SCHEMATHESIS_MAX_EXAMPLES=20` per operation
- `SCHEMATHESIS_MAX_FAILURES=5`
- generated resource names are prefixed with a per-run id
- created bookmarks and messages are cleaned up when practical
- `username` path parameters are forced to a non-existent namespaced value so
  user-blocking tests cannot hit built-in dev users

Set `SCHEMATHESIS_CLEANUP=false` only when deliberately exploring stateful
flows that need created resources to remain available.

## Reproducing Failures

Schemathesis prints a `Reproduce with:` block for failures and records crash
files in `.schemathesis/`. Re-run a failing case from this directory with:

```sh
st replay <case-id-or-crash-file>
```

The suite also writes `schemathesis-report/junit.xml` and the default
Schemathesis console output for CI and local debugging.

## CI

`.github/workflows/test-schemathesis-api.yml` is manual
(`workflow_dispatch`) so this showcase does not become an accidental merge
gate. The workflow builds one selected backend image, starts the minimum
runtime (`postgres`, `keycloak`, and that backend), waits for `/readyz`, and
runs the same helper with a bounded example count.

The workflow is meant for exploratory OpenAPI fuzzing across backends. Promote
it to a required gate only with an explicit docs and CI change that explains
why Schemathesis now defines correctness alongside `conformance/`.

## Limitations

The suite validates what the OpenAPI document can express: status codes,
content types, response schemas, and server-error properties under generated
inputs. Business rules from `docs/SPEC.md` still belong to the Playwright
conformance tests. Some Stackverse rules, such as ownership masking,
moderation transitions, and ETag invalidation across writes, need semantic
test setup and are intentionally not reimplemented here.

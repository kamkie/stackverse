# OWASP ZAP Baseline Security Smoke

This showcase runs the OWASP ZAP baseline scan against a composed Stackverse
gateway. It is a passive security smoke scan, not a full penetration test and
not a replacement for CodeQL, `conformance/`, or `e2e/`.

The ZAP baseline script spiders the target for a short bounded window, waits
for passive scanning, and writes reviewable reports. It does not run the active
attack phase used by ZAP full scans.

## Target

The suite scans the gateway:

```text
STACKVERSE_URL=http://localhost:8000
```

The helper scripts run ZAP in Docker. When `STACKVERSE_URL` points at
`localhost` or `127.0.0.1`, the scripts convert the in-container target to
`host.docker.internal` so the scanner can reach the host-published gateway.
Set `ZAP_TARGET_URL` to override that conversion, for example when scanning
from a custom Docker network.

## Local Run

Start a real stack first, using dev mode or containers from the repo root:

```sh
./scripts/run-stack.sh
```

```powershell
./scripts/run-stack.ps1
```

Then run the baseline scan from the repo root:

```sh
./scripts/zap-security.sh
```

```powershell
./scripts/zap-security.ps1
```

Reports are written to `testing/zap-security/reports/`:

- `zap-baseline.html`
- `zap-baseline.md`
- `zap-baseline.json`

Useful overrides:

| Variable | Default | Purpose |
|---|---|---|
| `STACKVERSE_URL` | `http://localhost:8000` | Gateway URL as seen by the host |
| `ZAP_TARGET_URL` | derived from `STACKVERSE_URL` | URL passed to the ZAP container |
| `ZAP_DOCKER_IMAGE` | `ghcr.io/zaproxy/zaproxy:stable` | Scanner image |
| `ZAP_REPORT_DIR` | `testing/zap-security/reports` | Report output directory |
| `ZAP_CONFIG_FILE` | `testing/zap-security/zap-baseline.conf` | Baseline rule config |
| `ZAP_SPIDER_MINUTES` | `1` | Traditional spider duration |
| `ZAP_MAX_MINUTES` | `5` | Max startup/passive-scan wait |
| `ZAP_FAIL_ON_WARNINGS` | `false` | Set `true` to let WARN findings return ZAP exit code 2 |
| `ZAP_DOCKER_NETWORK` | unset | Optional Docker network for the scanner container |

Extra command-line arguments are passed to `zap-baseline.py`.

## Findings And Suppressions

`zap-baseline.conf` keeps local-development expectations as `WARN`, not
`IGNORE`, so reports still show them. No rule is currently suppressed. The
helper passes ZAP's `-I` option by default, which means WARN-only scans do not
fail. A real `FAIL` rule or a ZAP execution error still fails the command.

Expected local HTTP findings can include:

- readable `XSRF-TOKEN` cookie: required by the gateway's double-submit CSRF
  contract
- cookies without `Secure`: expected only on local HTTP development
- missing HSTS: the gateway emits it only when `PUBLIC_URL` is `https`
- anti-CSRF warnings on anonymous login/IdP pages: Stackverse API mutations are
  protected by the gateway CSRF check

Other warnings, including browser security header gaps, MIME-sniffing headers,
cacheability, server version disclosure, and cross-origin policy headers, are
review findings. Either fix the implementation under test or document an
explicit rationale before changing `zap-baseline.conf`.

Promote a rule to `FAIL` only when the finding should block the smoke scan.
Use `IGNORE` sparingly and document the reason here in the same change.

## CI

`.github/workflows/test-zap-security.yml` is manual
(`workflow_dispatch`) and builds the reference `spring-kotlin + yarp + react`
stack before running the same helper. It uploads the ZAP reports on every run.

The workflow is deliberately not triggered by `push` or `pull_request`, so it
does not become part of the `ci-ok` merge gate while the baseline is a showcase.

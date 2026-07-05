# Tracetest OpenTelemetry Showcase

This optional suite makes the Stackverse observability contract executable:
one API action through the gateway must produce one trace containing both
gateway and backend work. It is a testing-tool showcase, not a replacement for
the canonical `conformance/` and `e2e/` gates.

## Target

The suite starts the reference composed stack with observability enabled:

```text
spring-kotlin backend + yarp gateway + react frontend
OTEL_SDK_DISABLED=false
```

It adds a small OpenTelemetry Collector in front of the existing LGTM
container. The collector fans traces out to both Grafana LGTM and Tracetest,
while logs and metrics continue to LGTM only. For test assertions, the
collector copies the OpenTelemetry `service.name` resource attribute onto each
span because Tracetest selectors operate on span attributes.

## Local Run

Prerequisites are Docker, the reference images, and the Tracetest image. Build
the Stackverse images first if they do not already exist:

```sh
./scripts/build-images.sh spring-kotlin yarp react
```

```powershell
./scripts/build-images.ps1 -Backend spring-kotlin -Gateway yarp -Frontend react
```

Then run the trace test from the repo root:

```sh
./scripts/tracetest-otel.sh
```

```powershell
./scripts/tracetest-otel.ps1
```

Set `BUILD=1` (PowerShell: `-Build`) to build images before starting the
stack. Optional positional arguments pick another backend, gateway, and
frontend combination, using the same image naming convention as
`scripts/run-stack.*`. The helper runs under the isolated compose project
`stackverse-tracetest` (override with `STACKVERSE_TRACETEST_PROJECT`) and
uses `docker compose up --force-recreate` so the telemetry endpoint override
is applied every time. The Tracetest overlay does not publish the stack's
standard host ports; all probes use service names inside the compose network,
so the suite can run while another local Stackverse stack owns ports such as
5432, 8180, 8000, 3000, 4317, or 4318.

## What It Proves

The test sends:

```text
GET http://gateway:8000/api/v1/messages/bundle?lang=en
```

Tracetest then asserts:

- the gateway response is `200`
- a span with `service.name=stackverse-gateway` exists
- a span with `service.name=stackverse-backend` exists
- a backend span is a descendant of a gateway span in the same trace

That last assertion is the important one: it fails when the gateway handles
the request but does not propagate W3C trace context to the backend.

## Relationship to the Shared Docs

`docs/ARCHITECTURE.md` defines the trace propagation rule: one browser or API
action is one trace spanning gateway and backend work. This suite is the
optional executable check for that rule.

`docs/LOGGING.md` requires trace IDs on logs and OTLP export when telemetry is
enabled. This suite does not inspect log content; it verifies the trace spine
that log correlation depends on.

## Artifacts and Runtime

The helper writes JUnit output to:

```text
testing/tracetest-otel/reports/junit.xml
```

The suite is expected to take about 2-4 minutes locally after images are
present. It is opt-in and non-blocking because the observability stack is
dev-grade and trace coverage across all implementation combinations is still a
showcase surface.

## CI

`.github/workflows/test-tracetest-otel.yml` is manual-only
(`workflow_dispatch`). It builds the selected stack, runs the same helper, and
uploads the JUnit report plus compose logs. It should remain optional unless
the repository deliberately promotes trace assertions to a merge gate.

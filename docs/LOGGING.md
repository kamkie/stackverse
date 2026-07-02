# Logging requirements

Normative for every implementation — backends and gateways in full, frontends
where noted. Logging is a shared convention like the environment variables in
`backends/README.md`: implementations may use any idiomatic library, but the
observable behavior below must be identical across stacks. MUST / SHOULD / MAY
are used in the RFC 2119 sense.

Two boundaries up front:

- **Logs are diagnostics, not the audit trail.** The append-only audit trail
  (`docs/SPEC.md` rule 16) is a product feature living in the database with its
  own API. Never treat log output as a substitute, and never rely on logs for
  anything the spec requires to be auditable.
- **Log aggregation infrastructure is out of scope** (`docs/INTENT.md`). The
  repo ships a dev-grade Loki via the `observability` profile; production
  retention, alerting, and index design belong to whoever operates a real
  deployment. These requirements make the *emitting side* production-ready.

## 1. Where logs go

- Applications MUST write logs to **stdout/stderr only** (12-factor). No log
  files, no rotation, no in-process shipping — the platform owns capture.
  (Dev mode tees terminals to `.logs/<module>.log` via `scripts/dev-stack.*`;
  that is the harness's doing, not the application's.)
- When telemetry is enabled (`OTEL_SDK_DISABLED=false`), applications MUST
  also export log records over OTLP using the standard `OTEL_*` variables, so
  they land in Loki next to the Tempo traces (see `docs/RUNNING.md`). With
  telemetry disabled the console stream is the only output.
- Containers MUST NOT change logging behavior based on "environment names" —
  there are no profiles; the environment variables are the configuration.

## 2. Event shape

- One event per line. Multi-line payloads (stack traces excepted) MUST be
  encoded, not emitted raw — a crafted input must never be able to forge a log
  line (see §6).
- Every event MUST carry: a UTC timestamp with at least millisecond precision
  (ISO-8601), a severity level, a logger/category name, and the message.
- Service identity comes from `OTEL_SERVICE_NAME` and is attached by the OTLP
  exporter; console output SHOULD NOT repeat it (one service per container).
- When a trace context exists, exported log records MUST carry `trace_id` and
  `span_id`, and console output SHOULD include the trace id — that link is
  what makes a log line actionable in Grafana.
- Console output MAY be human-readable text; the structured channel is OTLP.
  An implementation MAY offer JSON console output, but it is not required by
  the contract.

## 3. Severity levels

| Level | Meaning | Examples |
|---|---|---|
| `ERROR` | Unexpected failure, someone should look | unhandled exception → 5xx, DB connectivity lost, OIDC discovery failed at runtime |
| `WARN` | Degraded or suspicious, self-healing | token refresh failed (session destroyed, degraded to anonymous), retry succeeded, config fallback taken |
| `INFO` | Lifecycle and coarse operational facts | listening port, migrations applied, message seed imported, shutdown started |
| `DEBUG` | Developer detail, off in production | per-request internals, cache decisions, SQL |

- The default level MUST be `INFO`.
- Expected client behavior is **not** an application error: validation
  failures, `401`, `403`, `404`, and CSRF rejections MUST NOT log above
  `INFO` (spikes in those are an alerting concern on metrics, not a stack
  trace concern). `5xx` responses MUST log at `ERROR` with the stack trace.

## 4. What must be logged

- **Startup**: effective configuration at `INFO` — ports, DB host/name,
  issuer URI, upstream URLs — with secrets redacted (§6). A service that
  refuses to start MUST say why at `ERROR` before exiting non-zero.
- **Schema migrations**: each applied migration at `INFO` (backends own their
  schema; the log is the only record of what ran when).
- **Shutdown**: signal received and completion at `INFO`, so restarts are
  distinguishable from crashes.
- **Per-request records**: when tracing is enabled, the OTEL server spans are
  the per-request record and satisfy this requirement. Implementations MAY
  additionally emit console access logs; if they do, the fields are method,
  route, status, duration, and the authenticated `preferred_username` (never
  the token), and `/healthz`/`/readyz` probes MUST be excluded — probe noise
  drowns real traffic at one line per few seconds.
- **Security-relevant events** at `WARN`: a blocked user rejected, a session
  that could not be refreshed and was destroyed, repeated CSRF failures.
  These are diagnostics; the authoritative record is the audit trail.

## 5. What must never be logged

- Access, refresh, and ID tokens; `Authorization` and `Cookie`/`Set-Cookie`
  headers; session cookie values; the OIDC client secret; passwords; DB
  credentials. Not at any level, not truncated, not "just in dev".
- Request and response bodies, by default. If a specific diagnostic needs a
  body excerpt at `DEBUG`, it MUST be length-capped and field-allowlisted —
  bodies contain whatever users typed.
- Anything client-controlled (URLs, titles, usernames in messages) MUST be
  sanitized before logging: strip control characters, encode newlines, cap
  length. The Vite client-log forwarder does exactly this
  (`frontends/react/vite.config.ts`) — server-side logs get no weaker rule.

## 6. Correlation

- The gateway MUST propagate W3C `traceparent` to the backend on proxied
  requests when telemetry is enabled, so one browser action is one trace
  across gateway and backend.
- Log records exported over OTLP inherit that context (§2); nothing else —
  no custom correlation-ID headers. The repo standardizes on OpenTelemetry.

## 7. Configuration

| Variable | Default | Purpose |
|---|---|---|
| `LOG_LEVEL` | `info` | minimum console severity: `error`, `warn`, `info`, `debug` — mapped to the stack's native mechanism |
| `OTEL_SDK_DISABLED` | `true` | `false` additionally exports logs (with traces/metrics) over OTLP |

`LOG_LEVEL` follows the repo's env-only configuration rule: no logging config
files, no per-environment profiles. `debug` MUST be safe to enable in
production in the sense of §5 — verbosity is allowed to cost performance,
never confidentiality.

## 8. Frontends

- Production bundles MUST NOT `console.log` in normal operation; the browser
  console is reserved for actual errors (uncaught exceptions, failed
  requests). Users' consoles are not a log sink.
- Dev mode mirrors the browser console to the dev server (`[browser]` lines
  in the frontend terminal and `.logs/frontend.log`) — that forwarding MUST
  stay dev-only, sanitize its input, and never re-log into the app (a log
  loop is worse than no logs).

## 9. Conformance

| Requirement | spring-kotlin | yarp | react |
|---|---|---|---|
| stdout-only logging | ✅ | ✅ | n/a |
| OTLP log export behind `OTEL_SDK_DISABLED` | ✅ (Java agent) | ✅ (.NET SDK) | n/a |
| startup/migration/shutdown lifecycle at `INFO` | ✅ | ✅ | n/a |
| expected 4xx not logged as errors | ✅ | ✅ | n/a |
| secrets kept out of logs | ✅ | ✅ | ✅ |
| `LOG_LEVEL` honored | ❌ gap | ❌ gap | n/a |
| trace id on console lines when tracing on | ❌ gap | ❌ gap | n/a |
| dev-only console forwarding, sanitized | n/a | n/a | ✅ |

Gaps are tracked here on purpose: a new implementation must satisfy every row,
and the two `❌` rows are the agreed backlog for the existing ones.

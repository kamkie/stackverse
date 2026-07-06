# Gateway: Spring Cloud Gateway (Kotlin)

The Stackverse BFF on Spring Boot 4 / Spring Cloud Gateway (WebFlux) in Kotlin, on
Java 25. Route contract, cookie rules, and the login sequence live in
[docs/ARCHITECTURE.md](../../docs/ARCHITECTURE.md); shared env vars in
[gateways/README.md](../README.md).

## How it maps to the contract

| Contract | Here |
|---|---|
| `GET /auth/login` | Spring Security's authorization-request redirect filter, re-anchored from `/oauth2/authorization/{id}` to `/auth/login` via a custom resolver (code flow + PKCE S256) |
| `GET /auth/callback` | `oauth2Login`'s authentication filter with a custom `authenticationMatcher` |
| `POST /auth/logout` | controller: server-to-server RP-initiated logout at Keycloak, session invalidated, `204` |
| `GET /auth/session` | controller reading the session principal (`preferred_username`) |
| `/api/**` | gateway route to `BACKEND_URL` with a token-relay filter backed by Spring Security's authorized-client manager |
| `/**` | gateway route to the `FRONTEND_URL` SPA upstream when set; otherwise static resources + an `index.html` fallback filter |

## Design notes

- **Stateless process.** The `stackverse_session` cookie carries only the WebSession
  id; the session — security context and OAuth2 tokens included — lives in Redis via
  Spring Session ([SecurityConfig.kt](src/main/kotlin/dev/stackverse/gateway/config/SecurityConfig.kt)
  pins the tokens into the session with `WebSessionServerOAuth2AuthorizedClientRepository`;
  the default would cache them in-process). In-flight OIDC state (authorization
  request, PKCE verifier, nonce) is session data too, so two gateways behind a dumb
  load balancer need no affinity.
- **Token refresh** uses Spring Security's
  `ReactiveOAuth2AuthorizedClientManager` with the framework refresh-token
  provider and `WebClientReactiveRefreshTokenTokenResponseClient`
  ([SecurityConfig.kt](src/main/kotlin/dev/stackverse/gateway/config/SecurityConfig.kt),
  [AccessTokenManager.kt](src/main/kotlin/dev/stackverse/gateway/relay/AccessTokenManager.kt)).
  The local adapter only keeps the contract-specific decision that Spring's default
  failure handler cannot express on its own: rejected grants destroy the session and
  degrade to anonymous, while IdP outages keep the session and return 503.
  Concurrent requests may refresh twice; Keycloak allows refresh-token reuse by
  default, so both results are valid.
- **Refresh failures are two different animals** (semantics pinned in
  [docs/ARCHITECTURE.md](../../docs/ARCHITECTURE.md)). An IdP that *rejects* the
  refresh — an authoritative `400`/`401` from the token endpoint (RFC 6749 §5.2) —
  proves the session is dead, even when Spring cannot parse the OAuth error body:
  destroy it, `token_refresh_failed` at WARN, degrade the request to anonymous.
  An IdP that is *unavailable* — unreachable, answering `5xx`/`429`, or answering
  garbage without a `400`/`401` token-endpoint verdict — proves nothing about the
  session, so the manager logs `dependency_call_failed` at ERROR
  (`dependency=keycloak`) and errors with `IdpUnavailableException`; the relay
  filter translates it into a `503` problem document and the session — whose
  refresh token may still be valid — survives.
- **Logout without a redirect.** The contract wants `204` from `POST /auth/logout`,
  so RP-initiated logout happens server-to-server: a confidential-client POST of the
  refresh token to Keycloak's end-session endpoint tears down the SSO session
  ([RpInitiatedLogout.kt](src/main/kotlin/dev/stackverse/gateway/auth/RpInitiatedLogout.kt)).
- **Logout semantics: local-first, IdP best-effort.** The local session (Redis entry
  + cookie — the only credential the browser holds) is destroyed *first* because it
  is the only death the gateway can guarantee; the IdP revocation follows on its own
  subscription, detached from the request, with failures logged and swallowed. The
  full argument for this ordering lives in the yarp README and applies here
  verbatim.
- **Anonymous `/api/**` requests relay without a token.** The spec's public surface
  (public bookmark feeds, message reads) works logged-out; the backend owns
  per-endpoint auth. A session that can no longer refresh is destroyed and the
  request degrades to anonymous. CSRF and same-origin (`Origin` /
  `Sec-Fetch-Site`) violations → `403` problem document (mechanism pinned in
  [docs/ARCHITECTURE.md](../../docs/ARCHITECTURE.md)); token generation and cookie
  persistence use Spring Security's `CookieServerCsrfTokenRepository`, while the
  contract-specific problem body and same-origin checks stay in the gateway filter.
  The browser's cookies, the CSRF header, and any client-supplied `Authorization`
  header are stripped before proxying.
- **Callback failures redirect, never 500.** `error=access_denied` (the user pressed
  Cancel on the Keycloak form) and stale or replayed state both land in the
  authentication failure handler: log `oidc_callback_completed` outcome=failure at
  INFO — the failure *type* only, since the message can echo client-controlled query
  text — then redirect to `/` logged out.
- **Selective security headers.** Spring Security's default header writers are
  disabled because their `Cache-Control: no-store` stamp would break the backend's
  ETag caching exhibit on proxied responses. `SecurityHeadersWebFilter` applies the
  gateway contract's exact browser-hardening headers to SPA/auth responses, only
  `X-Content-Type-Options: nosniff` (and HTTPS-only HSTS) to `/api/**`, and never
  rewrites backend `Cache-Control`, `ETag`, or `304` behavior.
- **Observability** (docs/RUNNING.md) — the OpenTelemetry Java agent baked
  into the container image (auto-instruments WebFlux, the Netty proxy client,
  logging) — no SDK code in the application; active only when
  `OTEL_SDK_DISABLED=false`.
- **Logging** (docs/LOGGING.md) uses Spring Boot's built-in structured console
  logging (ECS flavor, UTC timestamps; the OTel agent's MDC puts trace/span ids on
  every line when telemetry is on) by default, the human-readable pattern for
  `LOG_FORMAT=text`. The contract's `event`/`outcome` fields travel as SLF4J
  key-value pairs via the small `logEvent` extension — the same convention as
  `backends/spring-kotlin`.
- **PKCE is forced.** Spring auto-enables PKCE only for public clients; the
  authorization-request customizer adds it for this confidential client too, so all
  gateway stacks exhibit identical wire behavior (the realm requires S256).

## Logging conformance

Status against the template in [docs/LOGGING.md](../../docs/LOGGING.md) §10;
`❌` rows are this implementation's agreed, visible backlog.

| Requirement | Status |
|---|---|
| stdout-only logging | ✅ |
| OTLP log export behind `OTEL_SDK_DISABLED` | ✅ (Java agent) |
| lifecycle events at `INFO` | ✅ |
| expected 4xx not logged as errors | ✅ |
| secrets kept out of logs | ✅ |
| `LOG_LEVEL` honored | ✅ |
| trace id on console lines when tracing on | ✅ |
| stable `event` names (§5: lifecycle, session, security, moderation) | ✅ |
| dependency events (§5: `dependency_call_failed`, `retry_exhausted`) | ❌ gap¹ |
| JSON console by default (`LOG_FORMAT`) | ✅ |
| dev-only console forwarding, sanitized | n/a |
| dev-only user-action log (§9: `[action]`/`[nav]`/`[api]`, no field values) | n/a |

¹ `dependency_call_failed` is emitted for Keycloak token-refresh outages, but
Redis and the backend upstream are still uncovered — partial coverage keeps
the row a gap.

## Configuration

All shared variables from [gateways/README.md](../README.md). `FRONTEND_URL`
is the normal path in compose and dev mode. If it is unset, `SPA_ROOT`
defaults to the bundled `classpath:/static` placeholder page. The OIDC issuer
metadata is resolved at startup, so Keycloak must be reachable when the gateway
boots (compose orders this with a healthcheck; `scripts/dev-stack.*` waits too).

This gateway honors `OIDC_INTERNAL_ISSUER_URI`: the JVM's HTTP clients dial only
the first resolved address, so compose's `localhost → host-gateway` alias trick
(which works for .NET's try-every-address client) cannot reach Keycloak from
inside the network. Spring Security parses the discovery document and builds the
client registration, then the token, JWKS, and end-session endpoints are re-based
onto the internal base while issuer validation and the browser-facing
authorization redirect keep `OIDC_ISSUER_URI` (the same escape hatch as the
backend's `OIDC_JWKS_URI`).

## Run

```sh
# infra first, from the repo root: docker compose up -d
./gradlew bootRun
# → http://localhost:8000, log in as demo/demo
```

## Test

Integration tests boot real Keycloak (realm imported from `infra/keycloak`) and
Redis via Testcontainers, start the gateway on a random port, and drive the real
authorization code flow plus token relay against a stub backend. Docker required.

```sh
./gradlew test
```

## Docker

```sh
docker build -t stackverse/gateway-spring-cloud-gateway:local .
# then from the repo root:
# GATEWAY_IMAGE=stackverse/gateway-spring-cloud-gateway:local docker compose --profile app up
```

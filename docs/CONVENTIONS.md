# Conventions

A cross-stack reference of the **common conventions** for each language/framework in Stackverse, and how each variant **follows or deliberately departs** from them. It answers "what does idiomatic *X* look like, and what did this repo do?" side by side.

This complements [INVARIANTS.md](INVARIANTS.md): §1 there defines what every stack must hold identical, and §2 names the single foundational pillar per stack — this document is the fuller, category-by-category idiom reference. The authoritative per-variant detail (and the reason behind each deliberate deviation) lives in each variant's own README, linked from the stack name in every table.

**Status legend:** ✅ idiomatic · 🟡 deliberate deviation (documented in the variant's README) · 🔴 undocumented deviation · — not applicable.

> Source trail: the per-variant issues (#145, #163–#183, #197–#198) and their closing cleanup PRs are the verified, adversarially-checked audit trail behind this snapshot. The current code plus the linked variant README are authoritative for each row; if they drift from this table, update the table.
>
> Maintenance note: unlike most shared docs this file is O(N) in the number of implementations — a new variant adds one row to each table in its layer section. Keep cells terse; put the *why* in the variant README, not here.

## Backends

### Project layout

| Stack | Idiomatic convention | This variant | Status |
|---|---|---|---|
| [Spring Boot (Kotlin)](../backends/spring-kotlin/README.md) | Package-by-feature slices, each owning entity/repo/service/controller | Package-by-feature (bookmark/message/moderation/account/audit/stats) | ✅ idiomatic |
| [Ktor (Kotlin)](../backends/ktor-kotlin/README.md) | Application.module() with plugins/ and routes/ split across files | Flat dev.stackverse.backend package; module + all routes in Application.kt | ✅ idiomatic |
| [ASP.NET Core](../backends/dotnet/README.md) | Minimal-API .NET 10 with feature folders, or MVC controllers | Minimal APIs, feature folders (Bookmarks/, Messages/...), static Map()+service per feature | ✅ idiomatic |
| [Go (chi)](../backends/go/README.md) | cmd/ entrypoint, internal/ for private packages, package-by-feature | cmd/backend main + internal/ feature packages (bookmarks, messages, auth, web...) | ✅ idiomatic |
| [Go (Echo)](../backends/go-echo/README.md) | cmd/ entrypoint, internal/ packages, Echo router setup at the HTTP edge | cmd/backend main + internal/ feature packages; Echo route/middleware wiring in app | ✅ idiomatic |
| [Grails](../backends/grails/README.md) | grails-app/{controllers,services,domain,conf} convention dirs; src/main/groovy for beans | Standard grails-app dirs; src/main/groovy holds Spring config and support | ✅ idiomatic |
| [Micronaut](../backends/micronaut-java/README.md) | Gradle Micronaut app, package-by-feature/layer under src/main/java | Standard Gradle layout; single flat dev.stackverse.backend package | ✅ idiomatic |
| [Node (Fastify/TS)](../backends/node-ts/README.md) | src/ with app/server split, routes as Fastify plugins, ESM + tsc build | src/ app.ts + server.ts split; feature routes in src/routes/*.ts; ESM, tsc to dist/ | ✅ idiomatic |
| [NestJS](../backends/node-nestjs/README.md) | Nest CLI feature modules: *.module/*.controller/*.service per domain | Nest CLI app with feature modules, controllers, and injectable services | ✅ idiomatic |
| [Open Liberty](../backends/open-liberty-java/README.md) | Maven WAR: src/main/java, webapp/WEB-INF, liberty server.xml config | Standard Maven WAR; single flat package, src/main/liberty/config/server.xml | ✅ idiomatic |
| [FastAPI](../backends/python-fastapi/README.md) | APIRouter per resource module, routers included on the app | `routers/` APIRouter modules by resource area, included from app setup | ✅ idiomatic |
| [Play (Scala)](../backends/play-scala/README.md) | conf/routes maps to app/controllers, models, services in standard app/* packages | Conventional Play layout: conf/routes, app/{controllers,services,repositories,models,support} | ✅ idiomatic |
| [Quarkus](../backends/quarkus-java/README.md) | Standard Maven src/main/java tree; JAX-RS resources per aggregate under one package | Maven layout; thin JAX-RS resources all delegate to one StackverseService | ✅ idiomatic |
| [Rust (Axum)](../backends/rust-axum/README.md) | Cargo crate with flat src/*.rs modules; workspace only when splitting libs | Single binary crate, flat modules (auth, config, db, error, handlers, logging) | ✅ idiomatic |
| [Ruby on Rails API](../backends/ruby-rails/README.md) | Rails API app with app/controllers, app/models, app/services, config/routes.rb | Standard Rails API layout; controllers plus contract-heavy services under app/services/stackverse | ✅ idiomatic |

### Persistence / data access

| Stack | Idiomatic convention | This variant | Status |
|---|---|---|---|
| [Spring Boot (Kotlin)](../backends/spring-kotlin/README.md) | Spring Data JPA/Hibernate, Flyway migrations, ddl-auto validate | Spring Data JPA + JpaSpecificationExecutor, Flyway V1, ddl-auto=validate, Postgres | ✅ idiomatic |
| [Ktor (Kotlin)](../backends/ktor-kotlin/README.md) | Exposed DSL/DAO over HikariCP, or a coroutine JDBC layer | Hand-written SQL via raw JDBC + HikariCP; Flyway migrations | 🟡 deliberate |
| [ASP.NET Core](../backends/dotnet/README.md) | EF Core + Npgsql, code-first migrations, LINQ queries | EF Core 10 + Npgsql, checked-in migrations, Database.Migrate() on startup, text[] tags + GIN | ✅ idiomatic |
| [Go (chi)](../backends/go/README.md) | pgx/database-sql with raw SQL; migrations via a tool or embedded files | pgxpool + hand-written SQL, no ORM; embedded SQL migrations under pg advisory lock | ✅ idiomatic |
| [Go (Echo)](../backends/go-echo/README.md) | pgx/database-sql with raw SQL; migrations via a tool or embedded files | pgxpool + hand-written SQL, no ORM; embedded SQL migrations under pg advisory lock | ✅ idiomatic |
| [Grails](../backends/grails/README.md) | GORM/Hibernate domain classes with dynamic finders | Raw Spring JdbcTemplate + hand-written SQL, Flyway migrations, no GORM | 🟡 deliberate |
| [Micronaut](../backends/micronaut-java/README.md) | Micronaut Data (JDBC/JPA) repositories with derived queries | Hand-rolled Database helper over HikariCP DataSource, raw SQL + Flyway | 🟡 deliberate |
| [Node (Fastify/TS)](../backends/node-ts/README.md) | Prisma/Drizzle/TypeORM, or plain pg with a query layer | plain pg, hand-written parameterized SQL, withTransaction helper, node-pg-migrate | 🟡 deliberate |
| [NestJS](../backends/node-nestjs/README.md) | TypeORM/Prisma via @nestjs/typeorm with injected repositories | Injectable feature services use raw pg, hand-written SQL, tiny withTransaction | 🟡 deliberate |
| [Open Liberty](../backends/open-liberty-java/README.md) | JPA/Hibernate entities via a Liberty JDBC dataSource | Explicit JDBC + HikariCP + Flyway migrations; tags as text[] with GIN index | 🟡 deliberate |
| [FastAPI](../backends/python-fastapi/README.md) | SQLAlchemy ORM (often async) with Alembic migrations | Plain psycopg3 raw SQL, dict_row, pool; hand-rolled SQL migration runner | 🟡 deliberate |
| [Play (Scala)](../backends/play-scala/README.md) | Slick (or Anorm/Quill) with Play DB pool for typed/async queries | Hand-written JDBC via thin Db helper on HikariCP + Flyway; raw SQL strings | 🟡 deliberate |
| [Quarkus](../backends/quarkus-java/README.md) | Hibernate ORM with Panache active-record/repository entities | Plain JDBC + hand-written SQL, custom RowMapper helpers, Flyway migrations | 🟡 deliberate |
| [Rust (Axum)](../backends/rust-axum/README.md) | SQLx/Diesel/SeaORM with explicit migrations | SQLx runtime-checked queries, FromRow structs, embedded SQL migrations | ✅ idiomatic |
| [Ruby on Rails API](../backends/ruby-rails/README.md) | ActiveRecord models and migrations over PostgreSQL | ActiveRecord migrations/models; explicit SQL for contract-sensitive queries and row locks | 🟡 deliberate |

### Dependency injection

| Stack | Idiomatic convention | This variant | Status |
|---|---|---|---|
| [Spring Boot (Kotlin)](../backends/spring-kotlin/README.md) | Constructor injection, @Service/@Configuration/@Bean components | Constructor injection throughout; @Service, @Configuration @Bean beans | ✅ idiomatic |
| [Ktor (Kotlin)](../backends/ktor-kotlin/README.md) | Koin, or Ktor 3 built-in dependencies plugin | Manual AppContext object graph wired by hand in main() | 🔴 undocumented |
| [ASP.NET Core](../backends/dotnet/README.md) | Built-in MS.Extensions.DI, constructor injection, scoped services | Built-in container; AddScoped services, endpoint-param injection, DbContext + auth via options | ✅ idiomatic |
| [Go (chi)](../backends/go/README.md) | manual constructor wiring in main/setup func, no DI container | app.New wires stores/APIs via NewX constructors passing pool+logger | ✅ idiomatic |
| [Go (Echo)](../backends/go-echo/README.md) | manual constructor wiring in main/setup func, no DI container | app.New wires stores/APIs and attaches Echo route middleware explicitly | ✅ idiomatic |
| [Grails](../backends/grails/README.md) | By-name Spring bean injection of services into controllers/services | Convention property injection for services; Spring @Configuration/@Bean for security | ✅ idiomatic |
| [Micronaut](../backends/micronaut-java/README.md) | Compile-time DI: constructor injection, @Singleton/@Controller, annotation processing | Constructor injection with @Singleton/@Controller/@Filter and annotation processors | ✅ idiomatic |
| [Node (Fastify/TS)](../backends/node-ts/README.md) | Fastify decorate/register plugin model; no container (or tsyringe/Nest if used) | Fastify decorate/hook/register + module-level singletons (pool, config, logger) | ✅ idiomatic |
| [NestJS](../backends/node-nestjs/README.md) | Nest IoC container: @Injectable providers, @Controller, constructor injection | Feature modules with @Controller classes and @Injectable services; global guard/filter providers | ✅ idiomatic |
| [Open Liberty](../backends/open-liberty-java/README.md) | CDI beans with @Inject; beans.xml bean-discovery | beans.xml present but no @Inject; JAX-RS getClasses() + static RuntimeSupport | 🟡 deliberate |
| [FastAPI](../backends/python-fastapi/README.md) | FastAPI Depends() for db sessions, current user, config | Depends for optional/current/role callers; db remains module-global pool | 🟡 deliberate |
| [Play (Scala)](../backends/play-scala/README.md) | Guice with per-collaborator @Inject constructor injection (Play default) | Guice-managed config, logger, Db, I18n, AuthService, startup, and controller components | ✅ idiomatic |
| [Quarkus](../backends/quarkus-java/README.md) | CDI/Arc: @ApplicationScoped beans, constructor @Inject, @Provider | @ApplicationScoped service, constructor @Inject, @Provider filters/mappers | ✅ idiomatic |
| [Rust (Axum)](../backends/rust-axum/README.md) | Axum/Tower state extractors; no DI container | Cloned AppState injected through State and middleware state | ✅ idiomatic |
| [Ruby on Rails API](../backends/ruby-rails/README.md) | Rails autoloading, controllers, concerns, service objects instead of a DI container | Zeitwerk-autoloaded controllers/models/services; module singletons for stateless helpers | ✅ idiomatic |

### Security / auth

| Stack | Idiomatic convention | This variant | Status |
|---|---|---|---|
| [Spring Boot (Kotlin)](../backends/spring-kotlin/README.md) | Spring Security OAuth2 resource server, stateless JWT, @PreAuthorize roles | OAuth2 resource server, STATELESS JWT, @EnableMethodSecurity + @PreAuthorize, custom filter | ✅ idiomatic |
| [Ktor (Kotlin)](../backends/ktor-kotlin/README.md) | Authentication plugin or named application plugin for auth context | Custom Nimbus JwtAuthenticator in a named application plugin; routes require identity/roles explicitly | ✅ idiomatic |
| [ASP.NET Core](../backends/dotnet/README.md) | JwtBearer against JWKS, authorization policies, fallback auth policy | JwtBearer vs Keycloak JWKS, fallback RequireAuthenticatedUser, per-endpoint role policies | ✅ idiomatic |
| [Go (chi)](../backends/go/README.md) | golang-jwt for JWT, net/http middleware; JWKS via a library | golang-jwt/jwt validates iss/aud/exp; hand-rolled cached JWKS fetch; chi middleware | 🟡 deliberate |
| [Go (Echo)](../backends/go-echo/README.md) | Echo middleware plus golang-jwt or echo-jwt with JWKS-backed verification | golang-jwt validates iss/aud/exp; hand-rolled cached JWKS fetch through Echo middleware adapters | 🟡 deliberate |
| [Grails](../backends/grails/README.md) | grails-spring-security-core plugin with annotations/interceptors | Raw Spring Security OAuth2 resource-server SecurityFilterChain, manual role checks | 🟡 deliberate |
| [Micronaut](../backends/micronaut-java/README.md) | micronaut-security with JWT/JWKS validation and @Secured role rules | @ServerFilter/@RequestFilter + custom Nimbus JwtVerifier, manual JWKS/OIDC and role checks | 🟡 deliberate |
| [Node (Fastify/TS)](../backends/node-ts/README.md) | JWT verify via jose/JWKS in an onRequest/preHandler hook, role guards | jose jwtVerify against Keycloak JWKS in one onRequest hook; requireCaller/requireRole | ✅ idiomatic |
| [NestJS](../backends/node-nestjs/README.md) | Passport guards / @UseGuards, AuthGuard, @Roles decorators | Global CanActivate guard verifies JWT via jose; services use requireCaller/requireRole helpers | 🟡 deliberate |
| [Open Liberty](../backends/open-liberty-java/README.md) | MicroProfile JWT / Jakarta Security declarative role checks | Manual Nimbus JWT validation in a ContainerRequestFilter | 🟡 deliberate |
| [FastAPI](../backends/python-fastapi/README.md) | JWT/OAuth2 via a Security() dependency (e.g. OAuth2, HTTPBearer) | JWKS/PyJWT verified through FastAPI dependencies; role aliases wrap require_role | 🟡 deliberate |
| [Play (Scala)](../backends/play-scala/README.md) | Play filters/action-builders or Silhouette/pac4j for auth | Nimbus JOSE JWT verified against Keycloak JWKS in AuthService; filters disabled | 🟡 deliberate |
| [Quarkus](../backends/quarkus-java/README.md) | SmallRye JWT bearer with @RolesAllowed/@Authenticated annotation RBAC | SmallRye JWT config-driven; proactive auth off; roles enforced manually via requireRole | 🔴 undocumented |
| [Rust (Axum)](../backends/rust-axum/README.md) | Tower/Axum middleware validating JWT and attaching request state | Axum middleware validates JWKS/JWT, provisions accounts, and exposes Identity in request extensions | ✅ idiomatic |
| [Ruby on Rails API](../backends/ruby-rails/README.md) | Rack middleware/controller before_action auth; JWT via a library | ApplicationController before_action validates JWKS/JWT and role helpers enforce endpoints | ✅ idiomatic |

### Error handling → HTTP

| Stack | Idiomatic convention | This variant | Status |
|---|---|---|---|
| [Spring Boot (Kotlin)](../backends/spring-kotlin/README.md) | @RestControllerAdvice + ProblemDetail (RFC 9457) | @RestControllerAdvice extends ResponseEntityExceptionHandler, ProblemDetail | ✅ idiomatic |
| [Ktor (Kotlin)](../backends/ktor-kotlin/README.md) | StatusPages plugin maps typed exceptions to responses | StatusPages maps ValidationProblem/ApiProblem to RFC-7807 Problem JSON | ✅ idiomatic |
| [ASP.NET Core](../backends/dotnet/README.md) | ProblemDetails via IExceptionHandler / AddProblemDetails middleware | Custom exception middleware maps ApiProblem types to hand-written RFC 9457 problem+json | 🟡 deliberate |
| [Go (chi)](../backends/go/README.md) | sentinel/typed errors; explicit status mapping at handler edge | *Problem type implements error, rendered as RFC 9457 problem+json | ✅ idiomatic |
| [Go (Echo)](../backends/go-echo/README.md) | Echo HTTPErrorHandler plus typed app errors at handler edge | Echo HTTPErrorHandler maps framework 404/405; *Problem renders RFC 9457 problem+json | ✅ idiomatic |
| [Grails](../backends/grails/README.md) | respond with errors, or UrlMappings error controllers | Spring @ControllerAdvice + ApiError to RFC7807 problem+json; UrlMappings 404/500 | ✅ idiomatic |
| [Micronaut](../backends/micronaut-java/README.md) | @Produces ExceptionHandler beans mapping exceptions to responses | ExceptionHandler&lt;ProblemException&gt; emitting RFC 7807 application/problem+json | ✅ idiomatic |
| [Node (Fastify/TS)](../backends/node-ts/README.md) | central setErrorHandler mapping typed errors to responses | setErrorHandler maps ApiProblem subclasses to RFC 9457 problem+json | ✅ idiomatic |
| [NestJS](../backends/node-nestjs/README.md) | Throw HttpException subclasses; Nest exception filters render responses | Global ProblemFilter renders ApiProblem/ValidationProblem and unexpected errors as RFC 9457 | ✅ idiomatic |
| [Open Liberty](../backends/open-liberty-java/README.md) | JAX-RS ExceptionMapper translating exceptions to responses | ExceptionMapper&lt;Throwable&gt; emitting RFC 7807 application/problem+json | ✅ idiomatic |
| [FastAPI](../backends/python-fastapi/README.md) | Raise HTTPException; FastAPI serializes the detail body | Custom AppProblem hierarchy + exception_handlers emitting RFC 9457 problem+json | ✅ idiomatic |
| [Play (Scala)](../backends/play-scala/README.md) | Play HttpErrorHandler / Result recovery for error responses | Custom ApiProblem hierarchy caught in controller api() wrapper, emits RFC 7807 problem+json | 🔴 undocumented |
| [Quarkus](../backends/quarkus-java/README.md) | JAX-RS ExceptionMapper providers translating exceptions to responses | ExceptionMapper providers emit RFC 9457 application/problem+json | ✅ idiomatic |
| [Rust (Axum)](../backends/rust-axum/README.md) | Implement IntoResponse for app errors and use Result<T, E> handlers | AppError implements IntoResponse; handlers short-circuit with Result<Response, AppError> | ✅ idiomatic |
| [Ruby on Rails API](../backends/ruby-rails/README.md) | rescue_from in ApplicationController or exceptions_app for API errors | ApplicationController rescue_from renders RFC 9457 problem+json from typed problem errors | ✅ idiomatic |

### Concurrency / async

| Stack | Idiomatic convention | This variant | Status |
|---|---|---|---|
| [Spring Boot (Kotlin)](../backends/spring-kotlin/README.md) | Servlet blocking model, @Transactional, pessimistic locks where needed | Web MVC blocking, @Transactional(readOnly), @Lock PESSIMISTIC_WRITE row locks | ✅ idiomatic |
| [Ktor (Kotlin)](../backends/ktor-kotlin/README.md) | suspend handlers; offload blocking work to Dispatchers.IO | suspend repos wrap blocking JDBC in withContext(Dispatchers.IO) | ✅ idiomatic |
| [ASP.NET Core](../backends/dotnet/README.md) | async/await end-to-end; DB races handled explicitly | async/await throughout; explicit FOR UPDATE transaction for the publish race | ✅ idiomatic |
| [Go (chi)](../backends/go/README.md) | goroutines + context; signal.NotifyContext for graceful shutdown | server goroutine, signal.NotifyContext, context propagation, FOR UPDATE row locks | ✅ idiomatic |
| [Go (Echo)](../backends/go-echo/README.md) | goroutines + context; signal.NotifyContext for graceful shutdown | net/http server with Echo handler, signal.NotifyContext, context propagation, FOR UPDATE row locks | ✅ idiomatic |
| [Grails](../backends/grails/README.md) | Synchronous servlet request handling; Promise/async only when needed | Synchronous JdbcTemplate calls under @Transactional; nothing async | ✅ idiomatic |
| [Micronaut](../backends/micronaut-java/README.md) | Blocking work offloaded via @ExecuteOn(TaskExecutors.BLOCKING) where needed | @RequestFilter offloads JWT/account JDBC; controller JDBC remains synchronous | 🔴 undocumented |
| [Node (Fastify/TS)](../backends/node-ts/README.md) | async/await, pooled connections, explicit locking for critical sections | async/await, pg.Pool, SELECT ... FOR UPDATE with documented lock ordering | ✅ idiomatic |
| [NestJS](../backends/node-nestjs/README.md) | async/await handlers; RxJS Observables for streams/interceptors | Plain async/await; SELECT ... FOR UPDATE row locks for moderation races | ✅ idiomatic |
| [Open Liberty](../backends/open-liberty-java/README.md) | Synchronous JAX-RS on container threads; @Suspended for async | Synchronous blocking JDBC on request threads; no async/reactive | ✅ idiomatic |
| [FastAPI](../backends/python-fastapi/README.md) | async handlers with an async driver (asyncpg/psycopg async) | Sync handlers + sync psycopg run on Starlette worker threadpool | 🟡 deliberate |
| [Play (Scala)](../backends/play-scala/README.md) | Action.async returning Future; non-blocking I/O off the request thread | Action.async wrappers run blocking JDBC on a bounded database-dispatcher | ✅ idiomatic |
| [Quarkus](../backends/quarkus-java/README.md) | Reactive Mutiny/Uni or auto-offloaded blocking on RESTEasy Reactive | Blocking JDBC on quarkus-rest; manual JDBC transactions, no Mutiny | ✅ idiomatic |
| [Rust (Axum)](../backends/rust-axum/README.md) | Tokio async handlers, SQLx futures, graceful shutdown | async Axum handlers over SQLx pool; shared state cloned per route/middleware | ✅ idiomatic |
| [Ruby on Rails API](../backends/ruby-rails/README.md) | Synchronous Rack request handling with pooled DB connections | Synchronous controllers, ActiveRecord transactions, SELECT ... FOR UPDATE row locks | ✅ idiomatic |

### Validation

| Stack | Idiomatic convention | This variant | Status |
|---|---|---|---|
| [Spring Boot (Kotlin)](../backends/spring-kotlin/README.md) | Bean Validation (jakarta.validation @Valid/@NotNull) on DTOs | Programmatic validation in services via custom Validator; no Bean Validation | 🟡 deliberate |
| [Ktor (Kotlin)](../backends/ktor-kotlin/README.md) | No standard library; manual checks in handlers/services | Hand-rolled Validator collecting FieldViolations per field | ✅ idiomatic |
| [ASP.NET Core](../backends/dotnet/README.md) | DataAnnotations or FluentValidation with model binding | Hand-rolled Validator collecting FieldViolations, thrown as ValidationProblem | 🟡 deliberate |
| [Go (chi)](../backends/go/README.md) | struct-tag validator (go-playground/validator) common; manual also fine | hand-rolled web.Validator collecting field errors, no validation library | 🟡 deliberate |
| [Go (Echo)](../backends/go-echo/README.md) | Echo binding plus go-playground/validator common; manual also fine | hand-rolled web.Validator collecting field errors, no validation library | 🟡 deliberate |
| [Grails](../backends/grails/README.md) | Domain constraints or @Validateable command objects | Manual validation in services building problem-detail error lists | 🟡 deliberate |
| [Micronaut](../backends/micronaut-java/README.md) | Bean Validation: @Valid on @Body with jakarta.validation constraints | Hand-rolled Validator collecting field violations; no @Valid despite http-validation dep | 🔴 undocumented |
| [Node (Fastify/TS)](../backends/node-ts/README.md) | Zod/TypeBox or Fastify JSON-schema type provider for request validation | hand-rolled Validator collecting field violations; no schema framework | 🟡 deliberate |
| [NestJS](../backends/node-nestjs/README.md) | class-validator/class-transformer DTOs with a global ValidationPipe | Hand-rolled Validator collector, no pipes, no DTO decorators | 🟡 deliberate |
| [Open Liberty](../backends/open-liberty-java/README.md) | Bean Validation (@Valid / Hibernate Validator) on inputs | Hand-rolled Validator collecting FieldViolations, keyed message localization | 🟡 deliberate |
| [FastAPI](../backends/python-fastapi/README.md) | Pydantic models bind and validate request bodies | Bodies bound as `Annotated[Any, Body()]`; hand-rolled Validator functions | 🟡 deliberate |
| [Play (Scala)](../backends/play-scala/README.md) | Play Form binding or JSON Reads/validate combinators | Manual Validator accumulator over Play-JSON asOpt lookups with message keys | 🔴 undocumented |
| [Quarkus](../backends/quarkus-java/README.md) | Hibernate Validator / Bean Validation via @Valid on mapped DTOs | Manual Validator over raw Jackson JsonNode collecting FieldViolations | 🔴 undocumented |
| [Rust (Axum)](../backends/rust-axum/README.md) | validator crate or explicit domain validation | Hand-written Validator collecting localized FieldViolations | 🟡 deliberate |
| [Ruby on Rails API](../backends/ruby-rails/README.md) | ActiveModel validations on models/form objects or dry-validation | Programmatic Validator collecting localized FieldViolations for RFC 9457 responses | 🟡 deliberate |

### Testing

| Stack | Idiomatic convention | This variant | Status |
|---|---|---|---|
| [Spring Boot (Kotlin)](../backends/spring-kotlin/README.md) | JUnit 5, @SpringBootTest/MockMvc, Testcontainers, spring-security-test | JUnit 5, @SpringBootTest + MockMvc + Testcontainers Postgres, injected JWTs | ✅ idiomatic |
| [Ktor (Kotlin)](../backends/ktor-kotlin/README.md) | testApplication from ktor-server-test-host with kotlin.test/JUnit5 | testApplication + kotlin.test/JUnit5; helper/unit tests only, no DB integration | ✅ idiomatic |
| [ASP.NET Core](../backends/dotnet/README.md) | xUnit unit tests plus WebApplicationFactory integration tests | xUnit unit tests plus no-container WebApplicationFactory tests over the minimal-API pipeline | ✅ idiomatic |
| [Go (chi)](../backends/go/README.md) | stdlib testing, table-driven tests, *_test.go beside code | stdlib testing, table-driven cases; gotestsum wraps for JUnit in CI | ✅ idiomatic |
| [Go (Echo)](../backends/go-echo/README.md) | stdlib testing, table-driven tests, httptest for router edges | stdlib testing, table-driven cases, Echo route smoke tests; gotestsum wraps for JUnit in CI | ✅ idiomatic |
| [Grails](../backends/grails/README.md) | Spock specifications, Grails unit/integration test traits | Spock specs for services and support helpers; unit-only, no integration tests | ✅ idiomatic |
| [Micronaut](../backends/micronaut-java/README.md) | @MicronautTest with injected HTTP client for integration tests | Plain JUnit 5 + AssertJ unit tests; controllers instantiated directly, no @MicronautTest | 🔴 undocumented |
| [Node (Fastify/TS)](../backends/node-ts/README.md) | Vitest/Jest, unit plus integration (supertest/inject) tests | Vitest unit tests on pure functions (validation, cursor, etag, i18n); no HTTP-level tests | ✅ idiomatic |
| [NestJS](../backends/node-nestjs/README.md) | Jest with @nestjs/testing TestingModule; supertest e2e | Vitest unit tests on pure functions/helpers, vi.mock, no TestingModule | 🟡 deliberate |
| [Open Liberty](../backends/open-liberty-java/README.md) | JUnit 5 units; Arquillian/liberty-maven integration tests | JUnit 5 (surefire + JaCoCo), one unit test; acceptance via shared conformance suite | ✅ idiomatic |
| [FastAPI](../backends/python-fastapi/README.md) | pytest with TestClient/httpx exercising the ASGI app end-to-end | pytest unit tests of helpers plus a small TestClient route smoke; no DB integration | 🟡 deliberate |
| [Play (Scala)](../backends/play-scala/README.md) | ScalaTestPlusPlay with GuiceApplicationBuilder for controller/app tests | ScalaTest AnyFunSuite unit tests of helpers; ResultSet faked via JDK dynamic proxies | 🔴 undocumented |
| [Quarkus](../backends/quarkus-java/README.md) | @QuarkusTest integration tests with RestAssured against live endpoints | Plain JUnit 5 unit tests with JDK dynamic-proxy mocks; contract via external conformance suite | 🔴 undocumented |
| [Rust (Axum)](../backends/rust-axum/README.md) | cargo test unit/integration tests, often with tower::ServiceExt for handlers | cargo test covers helpers, validation, pagination, language, and AppError rendering | ✅ idiomatic |
| [Ruby on Rails API](../backends/ruby-rails/README.md) | Minitest/Rails test, request tests, fixtures or factories | Minitest unit tests for helpers; HTTP contract covered by shared conformance suite | 🟡 deliberate |

### Formatter / linter

| Stack | Idiomatic convention | This variant | Status |
|---|---|---|---|
| [Spring Boot (Kotlin)](../backends/spring-kotlin/README.md) | ktlint or detekt wired into the Gradle build | No ktlint/detekt/spotless; only shared root .editorconfig | 🔴 undocumented |
| [Ktor (Kotlin)](../backends/ktor-kotlin/README.md) | ktlint or detekt (often via spotless) wired into Gradle | ktlintCheck wired into the Gradle build and implementation workflow | ✅ idiomatic |
| [ASP.NET Core](../backends/dotnet/README.md) | dotnet format / Roslyn analyzers, often enforced in CI | .editorconfig only (whitespace); no dotnet format or analyzer gate in CI | 🔴 undocumented |
| [Go (chi)](../backends/go/README.md) | gofmt/goimports + go vet; golangci-lint typical in CI | gofmt + go vet in CI; no golangci-lint config | 🔴 undocumented |
| [Go (Echo)](../backends/go-echo/README.md) | gofmt/goimports + go vet; golangci-lint typical in CI | go vet in CI; no gofmt check or golangci-lint config | 🔴 undocumented |
| [Grails](../backends/grails/README.md) | CodeNarc static analysis (grails default ruleset) | No CodeNarc or any linter/formatter configured | 🔴 undocumented |
| [Micronaut](../backends/micronaut-java/README.md) | No framework-enforced formatter; Spotless/Checkstyle optional | No Spotless/Checkstyle/PMD; only shared root .editorconfig | ✅ idiomatic |
| [Node (Fastify/TS)](../backends/node-ts/README.md) | ESLint + Prettier (or Biome) alongside tsc | tsc --noEmit (strict) only; no ESLint/Prettier/Biome config anywhere | 🔴 undocumented |
| [NestJS](../backends/node-nestjs/README.md) | ESLint (typescript-eslint) + Prettier from Nest CLI scaffold | ESLint + Prettier scripts alongside Nest CLI build/dev flow | ✅ idiomatic |
| [Open Liberty](../backends/open-liberty-java/README.md) | Spotless/Checkstyle or google-java-format via Maven plugin | No formatter or linter plugin configured in pom.xml | 🔴 undocumented |
| [FastAPI](../backends/python-fastapi/README.md) | Ruff (and/or Black + mypy) configured in pyproject | Ruff check + format check configured in pyproject and CI | ✅ idiomatic |
| [Play (Scala)](../backends/play-scala/README.md) | scalafmt (and often scalafix) config checked in | No scalafmt/scalafix config; only scalac -deprecation -feature flags | 🔴 undocumented |
| [Quarkus](../backends/quarkus-java/README.md) | Spotless/Checkstyle or IDE-standard formatting enforced in build | Spotless/google-java-format (AOSP style) runs in Maven `verify` | ✅ idiomatic |
| [Rust (Axum)](../backends/rust-axum/README.md) | rustfmt and cargo check/test; Clippy commonly added for larger crates | cargo fmt --check, cargo check, cargo test | ✅ idiomatic |
| [Ruby on Rails API](../backends/ruby-rails/README.md) | RuboCop plus rails-omakase or StandardRB in CI | No RuboCop/Standard configured; CI runs Zeitwerk and Minitest only | 🔴 undocumented |

### API & domain-type modeling

| Stack | Idiomatic convention | This variant | Status |
|---|---|---|---|
| [Spring Boot (Kotlin)](../backends/spring-kotlin/README.md) | Data-class DTOs distinct from entities, enums serialized to wire values | Separate request/response data classes; enums with @JsonValue + WebConfig converters | ✅ idiomatic |
| [Ktor (Kotlin)](../backends/ktor-kotlin/README.md) | kotlinx.serialization @Serializable data classes | Jackson (ktor-serialization-jackson) over plain data classes | 🟡 deliberate |
| [ASP.NET Core](../backends/dotnet/README.md) | records for DTOs, enums, nullable reference types enabled | sealed record DTOs, mutable entity classes, enums, Nullable enabled, global kebab-case enum policy | ✅ idiomatic |
| [Go (chi)](../backends/go/README.md) | plain structs with json tags; separate request/response DTOs | domain Bookmark vs request/Response DTOs; string consts for enums, no enum type | ✅ idiomatic |
| [Go (Echo)](../backends/go-echo/README.md) | plain structs with json tags; separate request/response DTOs | domain Bookmark vs request/Response DTOs; string consts for enums, no enum type | ✅ idiomatic |
| [Grails](../backends/grails/README.md) | Typed GORM domain classes; JSON views or respond marshalling | Untyped Maps end-to-end, lowercase wire-string enums, manual JSON render | 🟡 deliberate |
| [Micronaut](../backends/micronaut-java/README.md) | Java records for DTOs; enums for closed value sets | Records for DTOs/domain; String constants for enum-like wire values, text[] tags | ✅ idiomatic |
| [Node (Fastify/TS)](../backends/node-ts/README.md) | TS interfaces/types for DTOs and rows, union types for enums | row interfaces + as const union enums; lowercase wire strings; omitNulls drops nulls | ✅ idiomatic |
| [NestJS](../backends/node-nestjs/README.md) | DTO classes + entities; enums; @nestjs/swagger OpenAPI generation | Plain TS interfaces, string-literal unions, wire-string enums, omitNulls helper | 🟡 deliberate |
| [Open Liberty](../backends/open-liberty-java/README.md) | Typed DTOs bound by JSON-B/Jackson, often OpenAPI-generated | Map&lt;String,Object&gt; + Jackson for wire; records for internal inputs, no DTOs | 🔴 undocumented |
| [FastAPI](../backends/python-fastapi/README.md) | Pydantic models as request/response schemas with response_model | Plain dict[str, Any] in/out; dataclasses only for Caller and Config | 🟡 deliberate |
| [Play (Scala)](../backends/play-scala/README.md) | Json.format macros / Reads-Writes on case classes; typed IDs | Case-class rows plus manual JsObject builders (Wire.obj/Responses); string-typed enums | 🔴 undocumented |
| [Quarkus](../backends/quarkus-java/README.md) | Records/POJOs as request and response DTOs, framework-serialized | Records for domain/inputs; responses hand-built as LinkedHashMap, requests read from JsonNode | 🔴 undocumented |
| [Rust (Axum)](../backends/rust-axum/README.md) | Serde DTO structs, enums for closed sets, typed IDs | Serde request/response structs and FromRow rows; wire values kept as strings | ✅ idiomatic |
| [Ruby on Rails API](../backends/ruby-rails/README.md) | ActiveRecord models plus Jbuilder/serializers or render json hashes | ActiveRecord rows and hand-built response hashes with wire-string enums and nil omission | 🟡 deliberate |

## Gateways

### Proxy model

| Stack | Idiomatic convention | This variant | Status |
|---|---|---|---|
| [Spring Cloud Gateway](../gateways/spring-cloud-gateway/README.md) | Reactive Spring Cloud Gateway on WebFlux/Netty, non-blocking proxy | spring-cloud-starter-gateway-server-webflux; reactive Netty proxy | ✅ idiomatic |
| [Go (chi)](../gateways/go/README.md) | httputil.ReverseProxy with custom Director/ModifyResponse/ErrorHandler | NewSingleHostReverseProxy; Director strips Cookie/Auth, injects Bearer, per-proxy handlers | ✅ idiomatic |
| [Fastify](../gateways/node-fastify/README.md) | @fastify/http-proxy or fastify-reply-from plugin for upstream forwarding | @fastify/reply-from proxy with Stackverse-specific header/token/trace policy | ✅ idiomatic |
| [OpenResty (Lua)](../gateways/openresty/README.md) | Native proxy_pass to an upstream block | Manual lua-resty-http request_uri, buffers/re-emits response in content_by_lua | 🟡 deliberate |
| [YARP](../gateways/yarp/README.md) | YARP AddReverseProxy with route/cluster config plus request transforms | AddReverseProxy + AddTransforms; strips Cookie/CSRF/Authorization, injects Bearer | ✅ idiomatic |

### Routing configuration

| Stack | Idiomatic convention | This variant | Status |
|---|---|---|---|
| [Spring Cloud Gateway](../gateways/spring-cloud-gateway/README.md) | Routes via application.yaml or a RouteLocatorBuilder fluent bean | Programmatic RouteLocatorBuilder bean in RouteConfig.kt; header-strip + token relay | ✅ idiomatic |
| [Go (chi)](../gateways/go/README.md) | chi router with explicit method routes and middleware chain | chi.NewRouter with Get/Post/Handle/NotFound plus security + CSRF middleware | ✅ idiomatic |
| [Fastify](../gateways/node-fastify/README.md) | Fastify route methods with wildcard catch-alls for proxy paths | app.all("/api/*") and app.all("/*") plus explicit /auth/* GET/POST routes | ✅ idiomatic |
| [OpenResty (Lua)](../gateways/openresty/README.md) | nginx location blocks (=, prefix) in nginx.conf | Standard location blocks; PORT templated via envsubst at entrypoint | ✅ idiomatic |
| [YARP](../gateways/yarp/README.md) | Routes/clusters bound from appsettings.json ReverseProxy section | Programmatic RouteConfig/ClusterConfig via LoadFromMemory, not appsettings | 🔴 undocumented |

### Session / cookie handling

| Stack | Idiomatic convention | This variant | Status |
|---|---|---|---|
| [Spring Cloud Gateway](../gateways/spring-cloud-gateway/README.md) | Spring Session (Redis) over WebSession; cookie via CookieWebSessionIdResolver | Spring Session Redis; custom stackverse_session cookie, HttpOnly/Lax/Secure | ✅ idiomatic |
| [Go (chi)](../gateways/go/README.md) | opaque HttpOnly cookie keying server-side store; stateless process | 32-byte opaque key in HttpOnly Lax cookie; session in Redis via go-redis/v9 | ✅ idiomatic |
| [Fastify](../gateways/node-fastify/README.md) | @fastify/cookie; opaque session id in httpOnly cookie, tokens server-side | @fastify/cookie, opaque id in httpOnly lax stackverse_session cookie, tokens in Redis via ioredis | ✅ idiomatic |
| [OpenResty (Lua)](../gateways/openresty/README.md) | lua-resty-session + lua-resty-openidc, HttpOnly cookie, server-side store | lua-resty-session (storage=redis) + lua-resty-openidc, HttpOnly SameSite=Lax | ✅ idiomatic |
| [YARP](../gateways/yarp/README.md) | Cookie auth; server-side ticket store for stateless multi-instance | Cookie auth + Redis ITicketStore + Data Protection keys in Redis | ✅ idiomatic |

### CSRF & security headers

| Stack | Idiomatic convention | This variant | Status |
|---|---|---|---|
| [Spring Cloud Gateway](../gateways/spring-cloud-gateway/README.md) | Spring Security CsrfWebFilter and built-in header writers | CookieServerCsrfTokenRepository plus custom same-origin checks and contract problem responses | 🟡 deliberate |
| [Go (chi)](../gateways/go/README.md) | double-submit XSRF token, constant-time compare, hardening header middleware | XSRF-TOKEN/X-XSRF-TOKEN via subtle.ConstantTimeCompare plus Origin/Sec-Fetch checks | ✅ idiomatic |
| [Fastify](../gateways/node-fastify/README.md) | @fastify/helmet plus @fastify/csrf-protection for headers and tokens | Hand-rolled double-submit CSRF + Origin/Sec-Fetch-Site checks and manual header set in security.ts | 🟡 deliberate |
| [OpenResty (Lua)](../gateways/openresty/README.md) | No nginx built-in; hand-roll in Lua for a BFF | Double-submit XSRF token (constant-time), Origin/Sec-Fetch-Site, headers via header_filter | ✅ idiomatic |
| [YARP](../gateways/yarp/README.md) | Built-in IAntiforgery; UseHsts/UseSecurityHeaders for hardening | Hand-rolled double-submit CSRF + EdgeSecurity headers; CodeQL alert suppressed inline | 🟡 deliberate |

### Error handling

| Stack | Idiomatic convention | This variant | Status |
|---|---|---|---|
| [Spring Cloud Gateway](../gateways/spring-cloud-gateway/README.md) | Mono.error propagation; RFC 9457 problem responses for gateway-owned errors | onErrorResume maps IdpUnavailable to 503; Problems writes RFC 9457 documents | ✅ idiomatic |
| [Go (chi)](../gateways/go/README.md) | explicit error returns, sentinel errors, errors.Is; RFC 7807 problem+json | (val,ok,error) returns, errRefreshRejected/errIDPUnavailable sentinels, problem+json | ✅ idiomatic |
| [Fastify](../gateways/node-fastify/README.md) | setErrorHandler with RFC7807 application/problem+json responses | setErrorHandler plus sendProblem helper emitting problem+json (problems.ts) | ✅ idiomatic |
| [OpenResty (Lua)](../gateways/openresty/README.md) | Lua-emitted error bodies; format is app choice | RFC 7807 application/problem+json via problem.lua | ✅ idiomatic |
| [YARP](../gateways/yarp/README.md) | RFC 9457 problem+json responses; avoid leaking 500s | Problems.Write problem+json helper; OIDC callback failures redirect not 500 | ✅ idiomatic |

### Concurrency / async

| Stack | Idiomatic convention | This variant | Status |
|---|---|---|---|
| [Spring Cloud Gateway](../gateways/spring-cloud-gateway/README.md) | Reactor Mono/Flux, fully non-blocking; block() only at bootstrap | Reactor throughout; single startup .block() for OIDC discovery | ✅ idiomatic |
| [Go (chi)](../gateways/go/README.md) | goroutine-per-request net/http, context propagation, graceful shutdown | net/http server, signal.NotifyContext + Shutdown, ctx threaded through handlers | ✅ idiomatic |
| [Fastify](../gateways/node-fastify/README.md) | async/await handlers, native fetch or proxy plugins, stream large payloads | async/await throughout, @fastify/reply-from proxy, @fastify/static SPA files | ✅ idiomatic |
| [OpenResty (Lua)](../gateways/openresty/README.md) | Non-blocking cosockets (lua-resty-*), ngx.timer for background work | Cosocket lua-resty-http/redis, ngx.timer.at for OTLP export | ✅ idiomatic |
| [YARP](../gateways/yarp/README.md) | async/await end-to-end with CancellationToken propagation | async throughout, ValueTask transforms; hand-rolled refresh may double-refresh | 🟡 deliberate |

### Testing

| Stack | Idiomatic convention | This variant | Status |
|---|---|---|---|
| [Spring Cloud Gateway](../gateways/spring-cloud-gateway/README.md) | JUnit 5 + @SpringBootTest + Testcontainers integration tests | @SpringBootTest RANDOM_PORT, Testcontainers Keycloak+Redis, real code flow | ✅ idiomatic |
| [Go (chi)](../gateways/go/README.md) | stdlib testing + httptest fakes for upstreams; table-driven | testing + httptest harness faking backend/frontend/OIDC; gotestsum in CI | ✅ idiomatic |
| [Fastify](../gateways/node-fastify/README.md) | Vitest or node:test running Fastify in-process with injected requests | Vitest suite hosting buildApp in-process with MemorySessionStore and stubbed fetch | ✅ idiomatic |
| [OpenResty (Lua)](../gateways/openresty/README.md) | Test::Nginx (TAP) or busted | Hand-rolled Lua harness with ngx mock via resty; bespoke debug-hook LCOV | 🔴 undocumented |
| [YARP](../gateways/yarp/README.md) | xUnit + WebApplicationFactory integration tests | xUnit + WebApplicationFactory + Testcontainers (real Keycloak/Redis) | ✅ idiomatic |

### Formatter / linter

| Stack | Idiomatic convention | This variant | Status |
|---|---|---|---|
| [Spring Cloud Gateway](../gateways/spring-cloud-gateway/README.md) | ktlint, detekt, or spotless wired into the Gradle build | None configured; only shared root .editorconfig for whitespace | 🔴 undocumented |
| [Go (chi)](../gateways/go/README.md) | gofmt check plus golangci-lint (staticcheck) with committed config | CI runs go build + go vet only; no gofmt check, no golangci-lint, no config | 🔴 undocumented |
| [Fastify](../gateways/node-fastify/README.md) | ESLint plus Prettier (or Biome) enforced via a lint script | No ESLint/Prettier/Biome; only .editorconfig and tsc typecheck | 🔴 undocumented |
| [OpenResty (Lua)](../gateways/openresty/README.md) | luacheck (and often stylua) | No linter or formatter configured; CI only builds, config-tests, smoke-tests | 🔴 undocumented |
| [YARP](../gateways/yarp/README.md) | Roslyn analyzers + dotnet format, often warnings-as-errors in CI | Minimal shared .editorconfig only; no analyzers, format check, or warnings-as-errors | 🔴 undocumented |

## Frontends

### Component model

| Stack | Idiomatic convention | This variant | Status |
|---|---|---|---|
| [React](../frontends/react/README.md) | Function components with hooks; typed props interfaces | Function components, hooks, typed props interfaces, ReactNode/ReactElement | ✅ idiomatic |
| [Angular](../frontends/angular/README.md) | Standalone components, signal inputs/outputs, @if/@for control flow | Standalone @Component, input()/output(), @if/@for, inline templates | ✅ idiomatic |
| [Svelte 5](../frontends/svelte/README.md) | Runes-based .svelte components: $props, snippets, mount() bootstrap | Runes-based components with $props, snippets, and mount() bootstrap | ✅ idiomatic |
| [Vanilla TS](../frontends/vanilla-ts/README.md) | No framework; DOM/template functions, manual escaping, imperative rendering | Functions returning HTML template-literal strings, set via root.innerHTML + escapeHtml() | ✅ idiomatic |
| [Vue 3](../frontends/vue/README.md) | SFCs with &lt;script setup lang="ts"&gt;, typed defineProps/defineEmits | SFCs with &lt;script setup lang="ts"&gt;, generic defineProps/defineEmits macros | ✅ idiomatic |

### State / reactivity

| Stack | Idiomatic convention | This variant | Status |
|---|---|---|---|
| [React](../frontends/react/README.md) | TanStack Query for server state; Context/hooks for local | TanStack Query + Context (i18n/toast) + web-storage helpers; no global store | ✅ idiomatic |
| [Angular](../frontends/angular/README.md) | Signals (signal/computed/effect) with zoneless change detection | Plain signal stores + effect/untracked; zoneless; RxJS only at HttpClient | ✅ idiomatic |
| [Svelte 5](../frontends/svelte/README.md) | $state/$derived/$effect runes; stores mainly for cross-component shared state | $state/$derived/$effect for component state; stores retained for shared route/session/i18n state | ✅ idiomatic |
| [Vanilla TS](../frontends/vanilla-ts/README.md) | Plain mutable object; manual re-render, no reactive runtime | Single `state` object, full renderApp() re-render on every change | ✅ idiomatic |
| [Vue 3](../frontends/vue/README.md) | Composition API refs; Pinia for shared/global stores | Module-level ref() singletons in plain .ts files; no Pinia | 🔴 undocumented |

### Routing

| Stack | Idiomatic convention | This variant | Status |
|---|---|---|---|
| [React](../frontends/react/README.md) | react-router data router with nested route objects | react-router v8 createBrowserRouter, RouteObject[], nested layouts | ✅ idiomatic |
| [Angular](../frontends/angular/README.md) | @angular/router, provideRouter, lazy loadComponent/loadChildren | provideRouter with lazy loadComponent and loadChildren routes | ✅ idiomatic |
| [Svelte 5](../frontends/svelte/README.md) | SvelteKit file-based routing under src/routes | Hand-rolled History-API router (lib/route.ts store) with `{#if}` page dispatch in App.svelte | 🔴 undocumented |
| [Vanilla TS](../frontends/vanilla-ts/README.md) | Hand-rolled History API router; no router library | History API + popstate, data-link delegation, path switch in routeHtml | ✅ idiomatic |
| [Vue 3](../frontends/vue/README.md) | vue-router with createWebHistory and nested routes | vue-router createWebHistory, nested /admin children | ✅ idiomatic |

### API client & typing

| Stack | Idiomatic convention | This variant | Status |
|---|---|---|---|
| [React](../frontends/react/README.md) | Typed client generated from OpenAPI schema | openapi-fetch over openapi-typescript schema.ts, RFC 9457 unwrap | ✅ idiomatic |
| [Angular](../frontends/angular/README.md) | HttpClient + interceptors; types generated from OpenAPI | HttpClient (fetch) + csrf interceptor; hand-written types from spec | 🟡 deliberate |
| [Svelte 5](../frontends/svelte/README.md) | Generated types from OpenAPI; typed fetch wrapper | Hand-written fetch wrapper (ApiError) + types hand-transcribed from OpenAPI | 🟡 deliberate |
| [Vanilla TS](../frontends/vanilla-ts/README.md) | fetch wrappers; types from OpenAPI (codegen common, hand-writing fine) | Hand-written fetch wrappers; types.ts hand-transcribed from openapi.yaml, zero codegen | ✅ idiomatic |
| [Vue 3](../frontends/vue/README.md) | Typed fetch client generated from OpenAPI schema | openapi-fetch over openapi-typescript-generated schema.ts | ✅ idiomatic |

### Accessibility

| Stack | Idiomatic convention | This variant | Status |
|---|---|---|---|
| [React](../frontends/react/README.md) | Semantic HTML, labeled controls, ARIA wiring, roles | native &lt;dialog&gt;, useId label/aria-invalid/aria-describedby, roles, &lt;time&gt; | ✅ idiomatic |
| [Angular](../frontends/angular/README.md) | label/for, aria-invalid, roles, aria-live, native dialog | label/for, aria-invalid, role/aria-live/aria-label, native &lt;dialog&gt; showModal | ✅ idiomatic |
| [Svelte 5](../frontends/svelte/README.md) | Semantic elements, ARIA roles/labels, keyboard-operable controls | Semantic nav/time/headings, role/aria-label, role=alert, rel=noreferrer | ✅ idiomatic |
| [Vanilla TS](../frontends/vanilla-ts/README.md) | Semantic HTML, ARIA, native dialog, labelled controls | aria-*, roles, &lt;dialog&gt; showModal, scope=col, aria-live, visually-hidden labels | ✅ idiomatic |
| [Vue 3](../frontends/vue/README.md) | Semantic HTML, ARIA roles/labels, live regions, labeled fields | role=alert/status, aria-live, aria-label, label-wrapped fields | ✅ idiomatic |

### Project layout

| Stack | Idiomatic convention | This variant | Status |
|---|---|---|---|
| [React](../frontends/react/README.md) | Feature folders plus shared components dir | Feature folders (bookmarks, auth, i18n, pages) + components/ | ✅ idiomatic |
| [Angular](../frontends/angular/README.md) | Angular CLI src/app feature folders, suffix-less filenames (v20 style) | src/app feature folders; suffix-less filenames (bookmark-list.ts) | ✅ idiomatic |
| [Svelte 5](../frontends/svelte/README.md) | SvelteKit src/routes + src/lib; or Vite SPA src with App.svelte/main.ts | Vite SPA layout: src/{components,pages,lib,dev}, App.svelte, main.ts | ✅ idiomatic |
| [Vanilla TS](../frontends/vanilla-ts/README.md) | Flat src/ with entry module plus small focused modules | src/ main.ts monolith + api/i18n/types modules and dev/ helpers | ✅ idiomatic |
| [Vue 3](../frontends/vue/README.md) | src/ with components, views/pages, router, composables | src/ with components, pages, api, i18n, mocks, dev, test | ✅ idiomatic |

### Testing

| Stack | Idiomatic convention | This variant | Status |
|---|---|---|---|
| [React](../frontends/react/README.md) | Vitest + Testing Library, role queries, MSW mocks | Vitest + Testing Library + user-event, jsdom, shared MSW handlers | ✅ idiomatic |
| [Angular](../frontends/angular/README.md) | TestBed + HttpTestingController; Vitest now default over Karma/Jasmine | Vitest via @angular/build:unit-test, TestBed, HttpTestingController, jsdom | ✅ idiomatic |
| [Svelte 5](../frontends/svelte/README.md) | Vitest + @testing-library/svelte / vitest-browser-svelte for component tests | Vitest + jsdom for lib stores/helpers; no component tests | 🔴 undocumented |
| [Vanilla TS](../frontends/vanilla-ts/README.md) | Vitest + jsdom, colocated *.test.ts | Vitest + jsdom, colocated *.test.ts, junit + v8 coverage | ✅ idiomatic |
| [Vue 3](../frontends/vue/README.md) | Vitest + jsdom with @vue/test-utils for mounting | Vitest + jsdom, manual createApp mount helper, no @vue/test-utils | 🔴 undocumented |

### Formatter / linter

| Stack | Idiomatic convention | This variant | Status |
|---|---|---|---|
| [React](../frontends/react/README.md) | ESLint (+ Prettier) for lint and formatting | ESLint flat config for TypeScript, React Hooks, and React Refresh; no Prettier | ✅ idiomatic |
| [Angular](../frontends/angular/README.md) | angular-eslint + Prettier | Orphan .prettierrc (no prettier dep/script); no ESLint/angular-eslint | 🔴 undocumented |
| [Svelte 5](../frontends/svelte/README.md) | eslint-plugin-svelte + Prettier (prettier-plugin-svelte); svelte-check | No ESLint/Prettier config; svelte-check plus shared EditorConfig coverage | 🔴 undocumented |
| [Vanilla TS](../frontends/vanilla-ts/README.md) | ESLint + Prettier configured with a lint/format script | No ESLint/Prettier; strict tsc is the only static style/safety gate | 🔴 undocumented |
| [Vue 3](../frontends/vue/README.md) | ESLint (eslint-plugin-vue) + Prettier | No linter/formatter; vue-tsc type-check is the only static gate | 🔴 undocumented |

# Backend · ASP.NET Core (C#)

The Stackverse backend on .NET 10: minimal APIs, EF Core with Npgsql, JWT bearer
authentication against Keycloak's JWKS. Shared behavior, endpoints, and
environment variables are documented once in [backends/README.md](../README.md)
and the contract documents it points to — this file covers only what is
specific to this stack.

## Run it locally

Prerequisites: .NET SDK 10, the compose infra
(`docker compose up -d` at the repo root).

```sh
cd backends/dotnet
dotnet run --project src/StackverseBackend
```

Defaults match the compose infra (PostgreSQL on 5432, Keycloak on 8180); the
message seed resolves to the repo's `spec/messages` automatically. EF Core
migrations apply on startup — the database must be one this backend owns
(when switching from another backend: `docker compose down -v` first, see
[docs/RUNNING.md](../../docs/RUNNING.md)).

Tests (plain unit tests, no containers):

```sh
dotnet test
```

Conformance (the acceptance gate), with the backend running:

```sh
../../scripts/conformance.ps1        # or conformance.sh
```

## What this implementation demonstrates

- **Minimal APIs over controllers** — endpoint groups per feature
  (`Bookmarks/`, `Messages/`, `Moderation/`, ...), services carrying the
  business rules, no MVC layer. The idiomatic .NET 10 shape for a service of
  this size.
- **EF Core as the schema owner** — the model in `Data/AppDbContext.cs` maps
  to explicit snake_case DDL; `dotnet ef migrations add` generates the checked-in
  migrations (a local tool, `dotnet tool restore`), and `Database.Migrate()`
  applies them at startup.
- **PostgreSQL arrays for tags** — `tags text[]` with a GIN index instead of a
  join table; tag filters translate to array containment, tag counts go
  through `unnest`. A deliberate contrast with spring-kotlin's
  `bookmark_tags` collection table — same wire behavior, different storage
  idiom.
- **Keyset pagination in LINQ** — the v2 cursor listing is the same
  `(created_at, id)` walk as the reference backend, composed as `IQueryable`
  predicates.
- **Fallback authorization policy** — every endpoint requires an
  authenticated caller unless it opts out with `AllowAnonymous`; role
  endpoints declare the single role they need (`moderator`/`admin`) and the
  admin ⊃ moderator hierarchy stays in Keycloak.
- **Body-hash ETags in middleware** — `EtagMiddleware` buffers message/stats
  responses and derives the ETag from the bytes, the same stateless
  revalidation scheme as the reference backend's `ShallowEtagHeaderFilter`.

## Deliberate deviations worth comparing

- Enum wire values (`public`, `broken-link`, ...) come from one kebab-case
  `JsonNamingPolicy` applied globally, and the same strings are stored in the
  database — where spring-kotlin stores uppercase constant names and maps per
  enum with `@JsonValue`.
- `GET /api/v1/admin/stats` assembles its totals with several small queries
  (as the reference does) but the daily series uses two grouped raw-SQL
  queries; EF Core has no native `date_trunc` grouping over `timestamptz` at
  UTC.
- Validation is hand-rolled (`Common/Problems.cs`), not DataAnnotations:
  the contract wants all field errors collected in one RFC 9457 problem with
  localized messages, which is simpler to do directly than to bend the
  framework validators.

// Package store owns the PostgreSQL connection pool and this backend's schema
// (backends/README.md: each backend ships its own migrations and applies them
// on startup — swapping backends means a fresh database).
package store

import (
	"context"
	"embed"
	"errors"
	"fmt"
	"log/slog"
	"sort"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgtype"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/kamkie/stackverse/backends/go/internal/logx"
)

//go:embed migrations/*.sql
var migrations embed.FS

// migrationLock is the advisory-lock key serializing concurrent instances
// racing to migrate the same database at startup.
const migrationLock = 0x5741_434b // "STACK"-ish, any stable value works

// NowUTC is the single clock for server-managed timestamps, truncated to
// microseconds so in-memory values round-trip through PostgreSQL's
// timestamptz unchanged — a create response and a later read must serialize
// the same RFC 3339 string.
func NowUTC() time.Time {
	return time.Now().UTC().Truncate(time.Microsecond)
}

// Querier is the query surface shared by *pgxpool.Pool and pgx.Tx, so a store
// method can run standalone or inside a caller's transaction.
type Querier interface {
	Exec(ctx context.Context, sql string, arguments ...any) (pgconn.CommandTag, error)
	Query(ctx context.Context, sql string, args ...any) (pgx.Rows, error)
	QueryRow(ctx context.Context, sql string, args ...any) pgx.Row
}

// InTx runs fn in a transaction, committing on nil and rolling back on error —
// how a backoffice mutation and its audit entry stay atomic (SPEC rule 18).
func InTx(ctx context.Context, pool *pgxpool.Pool, fn func(tx pgx.Tx) error) error {
	tx, err := pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if err := fn(tx); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

// Open connects the pool and applies pending migrations.
func Open(ctx context.Context, dsn string, logger *slog.Logger) (*pgxpool.Pool, error) {
	config, err := pgxpool.ParseConfig(dsn)
	if err != nil {
		return nil, err
	}
	config.ConnConfig.Tracer = &dependencyTracer{logger: logger}
	// timestamptz values scan back in UTC, so what the service wrote
	// serializes byte-identically on later reads (RFC 3339 UTC on the wire)
	config.AfterConnect = func(ctx context.Context, conn *pgx.Conn) error {
		conn.TypeMap().RegisterType(&pgtype.Type{
			Name:  "timestamptz",
			OID:   pgtype.TimestamptzOID,
			Codec: &pgtype.TimestamptzCodec{ScanLocation: time.UTC},
		})
		return nil
	}
	pool, err := pgxpool.NewWithConfig(ctx, config)
	if err != nil {
		return nil, err
	}
	if err := migrate(ctx, pool, logger); err != nil {
		pool.Close()
		return nil, err
	}
	return pool, nil
}

func migrate(ctx context.Context, pool *pgxpool.Pool, logger *slog.Logger) error {
	entries, err := migrations.ReadDir("migrations")
	if err != nil {
		return err
	}
	names := make([]string, 0, len(entries))
	for _, entry := range entries {
		names = append(names, entry.Name())
	}
	sort.Strings(names)

	connection, err := pool.Acquire(ctx)
	if err != nil {
		return err
	}
	defer connection.Release()

	if _, err := connection.Exec(ctx, "select pg_advisory_lock($1)", migrationLock); err != nil {
		return err
	}
	defer func() { _, _ = connection.Exec(ctx, "select pg_advisory_unlock($1)", migrationLock) }()

	if _, err := connection.Exec(ctx,
		"create table if not exists schema_migrations (version text primary key, applied_at timestamptz not null)",
	); err != nil {
		return err
	}

	for _, name := range names {
		var applied bool
		err := connection.QueryRow(ctx,
			"select exists (select 1 from schema_migrations where version = $1)", name,
		).Scan(&applied)
		if err != nil {
			return err
		}
		if applied {
			continue
		}
		sql, err := migrations.ReadFile("migrations/" + name)
		if err != nil {
			return err
		}
		transaction, err := connection.Begin(ctx)
		if err != nil {
			return err
		}
		if _, err := transaction.Exec(ctx, string(sql)); err != nil {
			_ = transaction.Rollback(ctx)
			return fmt.Errorf("migration %s: %w", name, err)
		}
		if _, err := transaction.Exec(ctx,
			"insert into schema_migrations (version, applied_at) values ($1, $2)", name, NowUTC(),
		); err != nil {
			_ = transaction.Rollback(ctx)
			return fmt.Errorf("migration %s: %w", name, err)
		}
		if err := transaction.Commit(ctx); err != nil {
			return fmt.Errorf("migration %s: %w", name, err)
		}
		logx.Event(ctx, logger, slog.LevelInfo, "db_migration_applied", "success",
			"Applied database migration "+name,
			slog.String("migration", name),
		)
	}
	return nil
}

// dependencyTracer logs failing database calls as `dependency_call_failed`
// (docs/LOGGING.md §5). Integrity violations are excluded: they are expected
// client behavior surfacing as 409s (e.g. the report-race unique index), not
// dependency failures.
type dependencyTracer struct {
	logger *slog.Logger
}

type queryStartKey struct{}

func (t *dependencyTracer) TraceQueryStart(ctx context.Context, _ *pgx.Conn, _ pgx.TraceQueryStartData) context.Context {
	return context.WithValue(ctx, queryStartKey{}, time.Now())
}

func (t *dependencyTracer) TraceQueryEnd(ctx context.Context, _ *pgx.Conn, data pgx.TraceQueryEndData) {
	if data.Err == nil || errors.Is(data.Err, context.Canceled) {
		return
	}
	errorCode := "query_failed"
	var pgError *pgconn.PgError
	if errors.As(data.Err, &pgError) {
		// SQLSTATE class 23 — integrity constraint violation
		if strings.HasPrefix(pgError.Code, "23") {
			return
		}
		errorCode = pgError.Code
	}
	var durationMS int64
	if started, ok := ctx.Value(queryStartKey{}).(time.Time); ok {
		durationMS = time.Since(started).Milliseconds()
	}
	logx.Event(ctx, t.logger, slog.LevelError, "dependency_call_failed", "failure",
		"Database call failed",
		slog.String("dependency", "postgres"),
		slog.Int64("duration_ms", durationMS),
		slog.String("error_code", errorCode),
		slog.String("error", data.Err.Error()),
	)
}

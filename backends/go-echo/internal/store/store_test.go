package store

import (
	"context"
	"errors"
	"log/slog"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
)

type capturedRecord struct {
	attrs map[string]slog.Value
}

type captureHandler struct {
	records []capturedRecord
}

func (h *captureHandler) Enabled(context.Context, slog.Level) bool {
	return true
}

func (h *captureHandler) Handle(_ context.Context, record slog.Record) error {
	captured := capturedRecord{attrs: map[string]slog.Value{}}
	record.Attrs(func(attr slog.Attr) bool {
		captured.attrs[attr.Key] = attr.Value
		return true
	})
	h.records = append(h.records, captured)
	return nil
}

func (h *captureHandler) WithAttrs([]slog.Attr) slog.Handler {
	return h
}

func (h *captureHandler) WithGroup(string) slog.Handler {
	return h
}

func TestNowUTCIsUTCAndMicrosecondPrecision(t *testing.T) {
	now := NowUTC()
	if now.Location() != time.UTC {
		t.Fatalf("NowUTC location = %v, want UTC", now.Location())
	}
	if !now.Equal(now.Truncate(time.Microsecond)) {
		t.Fatalf("NowUTC must be truncated to microseconds, got %s", now.Format(time.RFC3339Nano))
	}
}

func TestDependencyTracerLogsFailedQueries(t *testing.T) {
	sink := &captureHandler{}
	tracer := &dependencyTracer{logger: slog.New(sink)}

	ctx := tracer.TraceQueryStart(context.Background(), nil, pgx.TraceQueryStartData{})
	tracer.TraceQueryEnd(ctx, nil, pgx.TraceQueryEndData{Err: errors.New("database unavailable")})

	if len(sink.records) != 1 {
		t.Fatalf("expected one dependency failure log, got %d", len(sink.records))
	}
	attrs := sink.records[0].attrs
	if got := attrs["event"].String(); got != "dependency_call_failed" {
		t.Fatalf("event = %q", got)
	}
	if got := attrs["dependency"].String(); got != "postgres" {
		t.Fatalf("dependency = %q", got)
	}
	if got := attrs["error_code"].String(); got != "query_failed" {
		t.Fatalf("error_code = %q", got)
	}
}

func TestDependencyTracerUsesPostgresErrorCode(t *testing.T) {
	sink := &captureHandler{}
	tracer := &dependencyTracer{logger: slog.New(sink)}

	tracer.TraceQueryEnd(context.Background(), nil, pgx.TraceQueryEndData{
		Err: &pgconn.PgError{Code: "57P01", Message: "admin shutdown"},
	})

	if len(sink.records) != 1 {
		t.Fatalf("expected one dependency failure log, got %d", len(sink.records))
	}
	if got := sink.records[0].attrs["error_code"].String(); got != "57P01" {
		t.Fatalf("error_code = %q", got)
	}
}

func TestDependencyTracerSkipsExpectedClientAndCanceledErrors(t *testing.T) {
	sink := &captureHandler{}
	tracer := &dependencyTracer{logger: slog.New(sink)}

	tracer.TraceQueryEnd(context.Background(), nil, pgx.TraceQueryEndData{Err: context.Canceled})
	tracer.TraceQueryEnd(context.Background(), nil, pgx.TraceQueryEndData{
		Err: &pgconn.PgError{Code: "23505", Message: "unique violation"},
	})

	if len(sink.records) != 0 {
		t.Fatalf("expected no logs for canceled or integrity errors, got %d", len(sink.records))
	}
}

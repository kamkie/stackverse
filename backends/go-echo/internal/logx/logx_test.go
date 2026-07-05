package logx

import (
	"context"
	"errors"
	"log/slog"
	"testing"
	"time"

	"go.opentelemetry.io/otel/trace"
)

type capturedRecord struct {
	level   slog.Level
	message string
	attrs   map[string]slog.Value
}

type captureHandler struct {
	enabled bool
	err     error
	records []capturedRecord
}

func (h *captureHandler) Enabled(context.Context, slog.Level) bool {
	return h.enabled
}

func (h *captureHandler) Handle(_ context.Context, record slog.Record) error {
	captured := capturedRecord{level: record.Level, message: record.Message, attrs: map[string]slog.Value{}}
	record.Attrs(func(attr slog.Attr) bool {
		captured.attrs[attr.Key] = attr.Value
		return true
	})
	h.records = append(h.records, captured)
	return h.err
}

func (h *captureHandler) WithAttrs([]slog.Attr) slog.Handler {
	return h
}

func (h *captureHandler) WithGroup(string) slog.Handler {
	return h
}

func TestLevelMapsConfiguredNames(t *testing.T) {
	cases := map[string]slog.Level{
		"error":   slog.LevelError,
		"WARN":    slog.LevelWarn,
		"debug":   slog.LevelDebug,
		"info":    slog.LevelInfo,
		"unknown": slog.LevelInfo,
		"":        slog.LevelInfo,
	}

	for input, want := range cases {
		if got := Level(input); got != want {
			t.Fatalf("Level(%q) = %v, want %v", input, got, want)
		}
	}
}

func TestEventAddsStableContractFields(t *testing.T) {
	sink := &captureHandler{enabled: true}
	logger := slog.New(sink)

	Event(context.Background(), logger, slog.LevelWarn, "authz_denied", "denied",
		"Denied a request", slog.String("actor", "demo"))

	if len(sink.records) != 1 {
		t.Fatalf("expected one record, got %d", len(sink.records))
	}
	record := sink.records[0]
	if record.level != slog.LevelWarn || record.message != "Denied a request" {
		t.Fatalf("unexpected record metadata: %+v", record)
	}
	if got := record.attrs["event"].String(); got != "authz_denied" {
		t.Fatalf("event = %q", got)
	}
	if got := record.attrs["outcome"].String(); got != "denied" {
		t.Fatalf("outcome = %q", got)
	}
	if got := record.attrs["actor"].String(); got != "demo" {
		t.Fatalf("actor = %q", got)
	}
}

func TestTraceContextHandlerAddsTraceIdentifiers(t *testing.T) {
	traceID := trace.TraceID{0x01, 0x02, 0x03}
	spanID := trace.SpanID{0x0a, 0x0b, 0x0c}
	ctx := trace.ContextWithSpanContext(context.Background(), trace.NewSpanContext(trace.SpanContextConfig{
		TraceID: traceID,
		SpanID:  spanID,
	}))

	sink := &captureHandler{enabled: true}
	handler := traceContextHandler{Handler: sink}
	record := slog.NewRecord(time.Now(), slog.LevelInfo, "with trace", 0)
	if err := handler.Handle(ctx, record); err != nil {
		t.Fatalf("Handle returned %v", err)
	}

	if len(sink.records) != 1 {
		t.Fatalf("expected one record, got %d", len(sink.records))
	}
	if got := sink.records[0].attrs["trace_id"].String(); got != traceID.String() {
		t.Fatalf("trace_id = %q, want %q", got, traceID.String())
	}
	if got := sink.records[0].attrs["span_id"].String(); got != spanID.String() {
		t.Fatalf("span_id = %q, want %q", got, spanID.String())
	}
}

func TestFanoutHandlerSwallowsSinkErrorsAndSkipsDisabledHandlers(t *testing.T) {
	failing := &captureHandler{enabled: true, err: errors.New("sink failed")}
	disabled := &captureHandler{enabled: false}
	handler := fanoutHandler{failing, disabled}

	if !handler.Enabled(context.Background(), slog.LevelInfo) {
		t.Fatal("fanout must be enabled when any wrapped handler is enabled")
	}

	record := slog.NewRecord(time.Now(), slog.LevelInfo, "fanout", 0)
	if err := handler.Handle(context.Background(), record); err != nil {
		t.Fatalf("fanout must swallow sink errors, got %v", err)
	}
	if len(failing.records) != 1 {
		t.Fatalf("enabled handler saw %d records, want 1", len(failing.records))
	}
	if len(disabled.records) != 0 {
		t.Fatalf("disabled handler saw %d records, want 0", len(disabled.records))
	}
}

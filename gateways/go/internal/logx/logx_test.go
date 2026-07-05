package logx

import (
	"context"
	"log/slog"
	"strings"
	"sync"
	"testing"

	"go.opentelemetry.io/otel/trace"
)

func TestLevelMapsKnownNamesAndDefaultsToInfo(t *testing.T) {
	tests := []struct {
		name string
		want slog.Level
	}{
		{name: "error", want: slog.LevelError},
		{name: "WARN", want: slog.LevelWarn},
		{name: "debug", want: slog.LevelDebug},
		{name: "info", want: slog.LevelInfo},
		{name: "unexpected", want: slog.LevelInfo},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := Level(tt.name); got != tt.want {
				t.Fatalf("level = %v, want %v", got, tt.want)
			}
		})
	}
}

func TestEventAddsStableContractFields(t *testing.T) {
	capture := &captureHandler{}
	logger := slog.New(capture)

	Event(context.Background(), logger, slog.LevelInfo, "csrf_validation_failed", "denied",
		"Rejected request",
		slog.String("error_code", "csrf_mismatch"))

	record := capture.onlyRecord(t)
	if record.Message != "Rejected request" {
		t.Fatalf("message = %q", record.Message)
	}
	attrs := attrsByName(record)
	if attrs["event"] != "csrf_validation_failed" {
		t.Fatalf("event = %#v", attrs["event"])
	}
	if attrs["outcome"] != "denied" {
		t.Fatalf("outcome = %#v", attrs["outcome"])
	}
	if attrs["error_code"] != "csrf_mismatch" {
		t.Fatalf("error_code = %#v", attrs["error_code"])
	}
}

func TestTraceContextHandlerAddsTraceFields(t *testing.T) {
	traceID, err := trace.TraceIDFromHex("00112233445566778899aabbccddeeff")
	if err != nil {
		t.Fatal(err)
	}
	spanID, err := trace.SpanIDFromHex("0102030405060708")
	if err != nil {
		t.Fatal(err)
	}
	ctx := trace.ContextWithSpanContext(context.Background(), trace.NewSpanContext(trace.SpanContextConfig{
		TraceID: traceID,
		SpanID:  spanID,
	}))
	capture := &captureHandler{}
	logger := slog.New(traceContextHandler{Handler: capture})

	logger.InfoContext(ctx, "correlated")

	attrs := attrsByName(capture.onlyRecord(t))
	if attrs["trace_id"] != traceID.String() {
		t.Fatalf("trace_id = %#v", attrs["trace_id"])
	}
	if attrs["span_id"] != spanID.String() {
		t.Fatalf("span_id = %#v", attrs["span_id"])
	}
}

func TestSanitizeEscapesControlCharactersAndCapsLength(t *testing.T) {
	got := Sanitize("ab\r\nc\x00defgh", 6)

	if strings.Contains(got, "\n") || strings.Contains(got, "\r") || strings.Contains(got, "\x00") {
		t.Fatalf("unsanitized control character in %q", got)
	}
	if !strings.Contains(got, `\n`) {
		t.Fatalf("escaped newline missing from %q", got)
	}
	if !strings.HasSuffix(got, "...") {
		t.Fatalf("length cap marker missing from %q", got)
	}
}

func attrsByName(record slog.Record) map[string]any {
	attrs := map[string]any{}
	record.Attrs(func(attr slog.Attr) bool {
		attrs[attr.Key] = attr.Value.Any()
		return true
	})
	return attrs
}

type captureHandler struct {
	mu      sync.Mutex
	records []slog.Record
}

func (h *captureHandler) Enabled(context.Context, slog.Level) bool {
	return true
}

func (h *captureHandler) Handle(_ context.Context, record slog.Record) error {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.records = append(h.records, record.Clone())
	return nil
}

func (h *captureHandler) WithAttrs([]slog.Attr) slog.Handler {
	return h
}

func (h *captureHandler) WithGroup(string) slog.Handler {
	return h
}

func (h *captureHandler) onlyRecord(t *testing.T) slog.Record {
	t.Helper()
	h.mu.Lock()
	defer h.mu.Unlock()
	if len(h.records) != 1 {
		t.Fatalf("records = %d", len(h.records))
	}
	return h.records[0]
}

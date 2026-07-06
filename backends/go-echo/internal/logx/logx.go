// Package logx configures structured logging per docs/LOGGING.md: JSON to
// stdout by default (LOG_FORMAT=text for local dev), LOG_LEVEL mapped to
// slog levels, trace ids attached to console lines whenever a span context
// exists, and an optional OTLP fan-out when telemetry is enabled.
package logx

import (
	"context"
	"log/slog"
	"os"
	"strings"

	"go.opentelemetry.io/otel/trace"
)

func Level(name string) slog.Level {
	switch strings.ToLower(name) {
	case "error":
		return slog.LevelError
	case "warn":
		return slog.LevelWarn
	case "debug":
		return slog.LevelDebug
	default:
		return slog.LevelInfo
	}
}

// New builds the console logger. extra handlers (the OTLP bridge) receive
// every record the console does; their own exporters do the filtering.
func New(level slog.Level, format string, extra ...slog.Handler) *slog.Logger {
	options := &slog.HandlerOptions{
		Level: level,
		ReplaceAttr: func(groups []string, attr slog.Attr) slog.Attr {
			// RFC 3339 UTC with millisecond precision (LOGGING.md §2)
			if attr.Key == slog.TimeKey && len(groups) == 0 {
				attr.Value = slog.StringValue(attr.Value.Time().UTC().Format("2006-01-02T15:04:05.000Z07:00"))
			}
			return attr
		},
	}
	var console slog.Handler
	if strings.EqualFold(format, "text") {
		console = slog.NewTextHandler(os.Stdout, options)
	} else {
		console = slog.NewJSONHandler(os.Stdout, options)
	}
	handlers := append([]slog.Handler{traceContextHandler{console}}, extra...)
	if len(handlers) == 1 {
		return slog.New(handlers[0])
	}
	return slog.New(fanoutHandler(handlers))
}

// Event emits a stable contract event (docs/LOGGING.md §5): `event` and
// `outcome` travel as structured fields while the message stays prose. Pass
// the request context so trace ids attach when tracing is on.
func Event(ctx context.Context, logger *slog.Logger, level slog.Level, event, outcome, message string, attrs ...slog.Attr) {
	fields := make([]slog.Attr, 0, len(attrs)+2)
	fields = append(fields, slog.String("event", event), slog.String("outcome", outcome))
	fields = append(fields, attrs...)
	logger.LogAttrs(ctx, level, message, fields...)
}

// traceContextHandler adds trace_id/span_id to console lines when the record's
// context carries a sampled span — the link into Grafana (LOGGING.md §2).
type traceContextHandler struct {
	slog.Handler
}

func (h traceContextHandler) Handle(ctx context.Context, record slog.Record) error {
	if span := trace.SpanContextFromContext(ctx); span.IsValid() {
		record.AddAttrs(
			slog.String("trace_id", span.TraceID().String()),
			slog.String("span_id", span.SpanID().String()),
		)
	}
	return h.Handler.Handle(ctx, record)
}

func (h traceContextHandler) WithAttrs(attrs []slog.Attr) slog.Handler {
	return traceContextHandler{h.Handler.WithAttrs(attrs)}
}

func (h traceContextHandler) WithGroup(name string) slog.Handler {
	return traceContextHandler{h.Handler.WithGroup(name)}
}

// fanoutHandler duplicates records to every wrapped handler; a failing sink
// must never block the others (LOGGING.md §1), so errors are swallowed.
type fanoutHandler []slog.Handler

func (h fanoutHandler) Enabled(ctx context.Context, level slog.Level) bool {
	for _, handler := range h {
		if handler.Enabled(ctx, level) {
			return true
		}
	}
	return false
}

func (h fanoutHandler) Handle(ctx context.Context, record slog.Record) error {
	for _, handler := range h {
		if handler.Enabled(ctx, record.Level) {
			_ = handler.Handle(ctx, record.Clone())
		}
	}
	return nil
}

func (h fanoutHandler) WithAttrs(attrs []slog.Attr) slog.Handler {
	wrapped := make(fanoutHandler, len(h))
	for i, handler := range h {
		wrapped[i] = handler.WithAttrs(attrs)
	}
	return wrapped
}

func (h fanoutHandler) WithGroup(name string) slog.Handler {
	wrapped := make(fanoutHandler, len(h))
	for i, handler := range h {
		wrapped[i] = handler.WithGroup(name)
	}
	return wrapped
}

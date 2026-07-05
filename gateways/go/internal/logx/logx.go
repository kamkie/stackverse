// Package logx configures structured logging per docs/LOGGING.md.
package logx

import (
	"context"
	"log/slog"
	"os"
	"strings"
	"unicode"

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

func New(level slog.Level, format string, extra ...slog.Handler) *slog.Logger {
	options := &slog.HandlerOptions{
		Level: level,
		ReplaceAttr: func(groups []string, attr slog.Attr) slog.Attr {
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

func Event(ctx context.Context, logger *slog.Logger, level slog.Level, event, outcome, message string, attrs ...slog.Attr) {
	fields := make([]slog.Attr, 0, len(attrs)+2)
	fields = append(fields, slog.String("event", event), slog.String("outcome", outcome))
	fields = append(fields, attrs...)
	logger.LogAttrs(ctx, level, message, fields...)
}

func Sanitize(value string, maxLength int) string {
	value = strings.ReplaceAll(value, "\r\n", "\n")
	var builder strings.Builder
	for _, ch := range value {
		if builder.Len() >= maxLength {
			builder.WriteString("...")
			break
		}
		switch {
		case ch == '\n' || ch == '\r':
			builder.WriteString(`\n`)
		case !unicode.IsControl(ch):
			builder.WriteRune(ch)
		}
	}
	return builder.String()
}

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

// Package web holds the HTTP plumbing shared by every feature: RFC 9457
// problem documents, JSON helpers, paging bounds, and the cross-cutting
// middleware (ETag revalidation, deprecation headers, panic recovery).
package web

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"runtime/debug"
	"strings"

	"github.com/kamkie/stackverse/backends/go-echo/internal/logx"
)

// Localizer resolves a message key to localized text for the current request
// (SPEC rule 11: language per rule 8, `en` fallback, then the key itself).
// Implemented by the messages feature; an interface here keeps the problem
// renderer free of a dependency cycle.
type Localizer interface {
	Localize(r *http.Request, key string) string
}

// FieldError is one field-level validation failure; the message is localized
// when the problem document is rendered.
type FieldError struct {
	Field      string
	MessageKey string
}

// Problem is an application error that maps 1:1 onto an RFC 9457 problem
// document. Services return it as an error; handlers render it.
type Problem struct {
	Status int
	Title  string
	Detail string
	// DetailKey, when set, is resolved through the Localizer into Detail.
	DetailKey string
	// Fields carries validation failures (SPEC rules 5 + 11).
	Fields []FieldError
}

func (p *Problem) Error() string {
	if p.Detail != "" {
		return p.Detail
	}
	return p.Title
}

// NotFound masks missing and deliberately hidden resources alike (SPEC rule 1:
// existence is not disclosed).
func NotFound() *Problem {
	return &Problem{Status: http.StatusNotFound, Title: "Not Found"}
}

func Unauthorized(detail string) *Problem {
	return &Problem{Status: http.StatusUnauthorized, Title: "Unauthorized", Detail: detail}
}

func Forbidden(detail string) *Problem {
	return &Problem{Status: http.StatusForbidden, Title: "Forbidden", Detail: detail}
}

func ForbiddenKey(detailKey string) *Problem {
	return &Problem{Status: http.StatusForbidden, Title: "Forbidden", DetailKey: detailKey}
}

func Conflict(detail string) *Problem {
	return &Problem{Status: http.StatusConflict, Title: "Conflict", Detail: detail}
}

func ConflictKey(detailKey string) *Problem {
	return &Problem{Status: http.StatusConflict, Title: "Conflict", DetailKey: detailKey}
}

func BadRequest(detail string) *Problem {
	return &Problem{Status: http.StatusBadRequest, Title: "Bad Request", Detail: detail}
}

// Validator collects field violations and yields one problem at the end, so
// all field errors are reported together.
type Validator struct {
	fields []FieldError
}

func (v *Validator) Reject(field, messageKey string) {
	v.fields = append(v.fields, FieldError{Field: field, MessageKey: messageKey})
}

func (v *Validator) Check(condition bool, field, messageKey string) {
	if !condition {
		v.Reject(field, messageKey)
	}
}

// Problem returns the collected validation problem, or nil when input is valid.
func (v *Validator) Problem() *Problem {
	if len(v.fields) == 0 {
		return nil
	}
	return &Problem{
		Status: http.StatusBadRequest,
		Title:  "Bad Request",
		Detail: "Request validation failed.",
		Fields: v.fields,
	}
}

type problemBody struct {
	Type   string              `json:"type"`
	Title  string              `json:"title"`
	Status int                 `json:"status"`
	Detail string              `json:"detail,omitempty"`
	Errors []problemFieldError `json:"errors,omitempty"`
}

type problemFieldError struct {
	Field      string `json:"field"`
	MessageKey string `json:"messageKey"`
	Message    string `json:"message"`
}

// WriteProblem renders a problem document, localizing the detail key and any
// field errors for the request's resolved language.
func WriteProblem(w http.ResponseWriter, r *http.Request, localizer Localizer, logger *slog.Logger, problem *Problem) {
	body := problemBody{Type: "about:blank", Title: problem.Title, Status: problem.Status, Detail: problem.Detail}
	if problem.DetailKey != "" {
		body.Detail = localizer.Localize(r, problem.DetailKey)
	}
	if len(problem.Fields) > 0 {
		// expected client behavior — a security signal, never above INFO
		// (docs/LOGGING.md §3); field names and keys are server-defined
		fields := make([]string, len(problem.Fields))
		for i, field := range problem.Fields {
			fields[i] = field.Field
		}
		logx.Event(r.Context(), logger, slog.LevelInfo, "input_validation_failed", "failure",
			"Request validation failed",
			slog.String("error_code", "validation_failed"),
			slog.String("fields", strings.Join(fields, ",")),
		)
		body.Errors = make([]problemFieldError, len(problem.Fields))
		for i, field := range problem.Fields {
			body.Errors[i] = problemFieldError{
				Field:      field.Field,
				MessageKey: field.MessageKey,
				Message:    localizer.Localize(r, field.MessageKey),
			}
		}
	}
	writeJSONWithContentType(w, problem.Status, "application/problem+json", body)
}

// Error renders err: a *Problem as its document, anything else as a 500 with
// an ERROR log (docs/LOGGING.md §3 — unexpected failures always log).
func Error(w http.ResponseWriter, r *http.Request, localizer Localizer, logger *slog.Logger, err error) {
	var problem *Problem
	if errors.As(err, &problem) {
	} else if r.Context().Err() == context.Canceled {
		// the client went away mid-request; nothing to report and nobody listening
		return
	} else {
		// 5xx logs at ERROR with a stack trace (docs/LOGGING.md §3); Go errors
		// carry no stack of their own, so capture the reporting site's
		logger.ErrorContext(r.Context(), "Unhandled error serving request",
			slog.String("error", err.Error()),
			slog.String("method", r.Method),
			slog.String("path", r.URL.Path),
			slog.String("stack", string(debug.Stack())),
		)
		problem = &Problem{Status: http.StatusInternalServerError, Title: "Internal Server Error"}
	}
	WriteProblem(w, r, localizer, logger, problem)
}

package web

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

type testLocalizer map[string]string

func (l testLocalizer) Localize(_ *http.Request, key string) string {
	if value, ok := l[key]; ok {
		return value
	}
	return key
}

func testLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

func TestDecodeJSONIgnoresUnknownFieldsAndRejectsMalformedBodies(t *testing.T) {
	var body struct {
		Title string `json:"title"`
	}
	request := httptest.NewRequest(http.MethodPost, "/x", strings.NewReader(`{"title":"hello","ignored":true}`))
	if problem := DecodeJSON(request, &body); problem != nil {
		t.Fatalf("valid JSON with unknown fields rejected: %v", problem)
	}
	if body.Title != "hello" {
		t.Fatalf("decoded title = %q", body.Title)
	}

	trailing := httptest.NewRequest(http.MethodPost, "/x", strings.NewReader(`{"title":"hello"} {}`))
	if problem := DecodeJSON(trailing, &body); problem == nil || problem.Status != http.StatusBadRequest {
		t.Fatalf("trailing JSON must be a 400 problem, got %v", problem)
	}

	malformed := httptest.NewRequest(http.MethodPost, "/x", strings.NewReader(`{"title":`))
	if problem := DecodeJSON(malformed, &body); problem == nil || problem.Status != http.StatusBadRequest {
		t.Fatalf("malformed JSON must be a 400 problem, got %v", problem)
	}
}

func TestPageSizeIgnoresPageParameterForCursorListings(t *testing.T) {
	request := httptest.NewRequest(http.MethodGet, "/api/v2/bookmarks?page=not-an-int&size=10", nil)
	size, problem := PageSize(request)
	if problem != nil {
		t.Fatalf("PageSize should ignore page for cursor listings: %v", problem)
	}
	if size != 10 {
		t.Fatalf("size = %d, want 10", size)
	}
}

func TestMaxLengthCountsRunes(t *testing.T) {
	if problem := MaxLength("ąβ", 2, "q"); problem != nil {
		t.Fatalf("two runes should fit length 2: %v", problem)
	}
	if problem := MaxLength("ąβc", 2, "q"); problem == nil || problem.Status != http.StatusBadRequest {
		t.Fatalf("three runes over length 2 should be a 400 problem, got %v", problem)
	}
}

func TestWriteProblemLocalizesDetailAndFieldErrors(t *testing.T) {
	localizer := testLocalizer{
		"error.account.blocked":     "Your account is blocked.",
		"validation.title.required": "Title is required.",
		"validation.url.invalid":    "URL is invalid.",
	}
	problem := &Problem{
		Status:    http.StatusBadRequest,
		Title:     "Bad Request",
		DetailKey: "error.account.blocked",
		Fields: []FieldError{
			{Field: "title", MessageKey: "validation.title.required"},
			{Field: "url", MessageKey: "validation.url.invalid"},
		},
	}

	response := httptest.NewRecorder()
	WriteProblem(response, httptest.NewRequest(http.MethodPost, "/x", nil), localizer, testLogger(), problem)

	if response.Code != http.StatusBadRequest {
		t.Fatalf("status = %d", response.Code)
	}
	if got := response.Header().Get("Content-Type"); got != "application/problem+json" {
		t.Fatalf("content type = %q", got)
	}
	var body problemBody
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		t.Fatalf("problem response is not JSON: %v", err)
	}
	if body.Detail != "Your account is blocked." {
		t.Fatalf("detail = %q", body.Detail)
	}
	if len(body.Errors) != 2 ||
		body.Errors[0].Message != "Title is required." ||
		body.Errors[1].Message != "URL is invalid." {
		t.Fatalf("localized errors = %+v", body.Errors)
	}
}

func TestErrorSkipsCanceledRequest(t *testing.T) {
	response := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodGet, "/x", nil)
	ctx, cancel := context.WithCancel(request.Context())
	cancel()

	Error(response, request.WithContext(ctx), testLocalizer{}, testLogger(), errors.New("client went away"))

	if response.Code != http.StatusOK {
		t.Fatalf("canceled request should not write a response, got status %d", response.Code)
	}
	if response.Body.Len() != 0 {
		t.Fatalf("canceled request should not write a body, got %q", response.Body.String())
	}
}

func TestRecoverConvertsPanicToProblemDocument(t *testing.T) {
	handler := Recover(testLocalizer{}, testLogger())(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
		panic("boom")
	}))

	response := httptest.NewRecorder()
	handler.ServeHTTP(response, httptest.NewRequest(http.MethodGet, "/panic", nil))

	if response.Code != http.StatusInternalServerError {
		t.Fatalf("panic status = %d", response.Code)
	}
	var body problemBody
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		t.Fatalf("panic problem is not JSON: %v", err)
	}
	if body.Title != "Internal Server Error" {
		t.Fatalf("panic problem title = %q", body.Title)
	}
}

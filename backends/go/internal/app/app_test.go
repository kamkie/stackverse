package app

import (
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/kamkie/stackverse/backends/go/internal/config"
)

func TestNewRouterServesHealthAndProblemNotFound(t *testing.T) {
	handler, _ := New(config.Config{
		IssuerURI: "http://localhost:8180/realms/stackverse",
		JWKSURI:   "http://localhost:8180/realms/stackverse/protocol/openid-connect/certs",
	}, nil, slog.New(slog.NewTextHandler(io.Discard, nil)))

	health := httptest.NewRecorder()
	handler.ServeHTTP(health, httptest.NewRequest(http.MethodGet, "/healthz", nil))
	if health.Code != http.StatusOK {
		t.Fatalf("healthz status = %d", health.Code)
	}
	var body map[string]string
	if err := json.Unmarshal(health.Body.Bytes(), &body); err != nil {
		t.Fatalf("healthz response is not JSON: %v", err)
	}
	if body["status"] != "up" {
		t.Fatalf("healthz status body = %q", body["status"])
	}

	missing := httptest.NewRecorder()
	handler.ServeHTTP(missing, httptest.NewRequest(http.MethodGet, "/no-such-route", nil))
	if missing.Code != http.StatusNotFound {
		t.Fatalf("missing route status = %d", missing.Code)
	}
	if got := missing.Header().Get("Content-Type"); got != "application/problem+json" {
		t.Fatalf("missing route content type = %q", got)
	}
}

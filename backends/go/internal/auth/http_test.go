package auth

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"
)

type localizerStub struct{}

func (localizerStub) Localize(_ *http.Request, key string) string {
	return key
}

func discardLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

func TestMeFiltersAndSortsApplicationRoles(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/api/v1/me", nil)
	req = req.WithContext(context.WithValue(req.Context(), contextKey{}, &Identity{
		Username: "admin",
		Name:     "Ada Admin",
		Email:    "admin@example.com",
		Roles:    []string{"offline_access", "moderator", "admin"},
	}))

	response := httptest.NewRecorder()
	Me(response, req)

	if response.Code != http.StatusOK {
		t.Fatalf("status = %d", response.Code)
	}
	var body meResponse
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		t.Fatalf("response is not JSON: %v", err)
	}
	if body.Username != "admin" || body.Name != "Ada Admin" || body.Email != "admin@example.com" {
		t.Fatalf("unexpected identity body: %+v", body)
	}
	if len(body.Roles) != 2 || body.Roles[0] != "admin" || body.Roles[1] != "moderator" {
		t.Fatalf("roles should include sorted application roles only, got %v", body.Roles)
	}
}

func TestRequireAuthRejectsAnonymousRequests(t *testing.T) {
	middleware := NewMiddleware(nil, "", "", localizerStub{}, discardLogger())
	called := false
	handler := middleware.RequireAuth(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
		called = true
	}))

	response := httptest.NewRecorder()
	handler.ServeHTTP(response, httptest.NewRequest(http.MethodGet, "/private", nil))

	if called {
		t.Fatal("anonymous request reached protected handler")
	}
	if response.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", response.Code)
	}
}

func TestRequireRoleRequiresExactRoleFromToken(t *testing.T) {
	middleware := NewMiddleware(nil, "", "", localizerStub{}, discardLogger())
	handler := middleware.RequireRole("moderator")(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))

	noModerator := httptest.NewRequest(http.MethodGet, "/moderator", nil)
	noModerator = noModerator.WithContext(context.WithValue(noModerator.Context(), contextKey{}, &Identity{
		Username: "admin",
		Roles:    []string{"admin"},
	}))
	denied := httptest.NewRecorder()
	handler.ServeHTTP(denied, noModerator)
	if denied.Code != http.StatusForbidden {
		t.Fatalf("admin without moderator composite role status = %d, want 403", denied.Code)
	}

	withModerator := httptest.NewRequest(http.MethodGet, "/moderator", nil)
	withModerator = withModerator.WithContext(context.WithValue(withModerator.Context(), contextKey{}, &Identity{
		Username: "moderator",
		Roles:    []string{"moderator"},
	}))
	allowed := httptest.NewRecorder()
	handler.ServeHTTP(allowed, withModerator)
	if allowed.Code != http.StatusNoContent {
		t.Fatalf("moderator status = %d, want 204", allowed.Code)
	}
}

package auth

import (
	"bytes"
	"context"
	"crypto/rsa"
	"encoding/json"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

func signTestToken(t *testing.T, key any, method jwt.SigningMethod, kid string, claims jwt.MapClaims) string {
	t.Helper()
	token := jwt.NewWithClaims(method, claims)
	token.Header["kid"] = kid
	signed, err := token.SignedString(key)
	if err != nil {
		t.Fatalf("sign token: %v", err)
	}
	return signed
}

func validTestClaims(issuer, audience string) jwt.MapClaims {
	return jwt.MapClaims{
		"iss":                issuer,
		"aud":                audience,
		"exp":                time.Now().Add(time.Hour).Unix(),
		"preferred_username": "demo",
		"name":               "Demo User",
		"email":              "demo@example.com",
		"realm_access":       map[string]any{"roles": []any{"moderator", "offline_access"}},
	}
}

func TestAuthenticateValidatesJWTAndCarriesIdentity(t *testing.T) {
	privateKey := generateTestRSAKey(t)
	const issuer = "https://issuer.example/realms/stackverse"
	const audience = "stackverse-api"
	keys := &Keys{
		keys:        map[string]*rsa.PublicKey{"test": &privateKey.PublicKey},
		lastRefresh: time.Now(),
		logger:      discardLogger(),
	}
	middleware := NewMiddleware(keys, issuer, audience, localizerStub{}, discardLogger())
	token := signTestToken(t, privateKey, jwt.SigningMethodRS256, "test", validTestClaims(issuer, audience))

	var received *Identity
	handler := middleware.Authenticate(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		received = FromContext(r.Context())
		w.WriteHeader(http.StatusNoContent)
	}))
	request := httptest.NewRequest(http.MethodGet, "/api/v1/me", nil)
	request.Header.Set("Authorization", "Bearer "+token)
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)

	if response.Code != http.StatusNoContent {
		t.Fatalf("status = %d, want 204; body=%s", response.Code, response.Body.String())
	}
	if received == nil || received.Username != "demo" || received.Name != "Demo User" || received.Email != "demo@example.com" {
		t.Fatalf("unexpected authenticated identity: %+v", received)
	}
	if !received.HasRole("moderator") || received.HasRole("admin") {
		t.Fatalf("unexpected authenticated roles: %v", received.Roles)
	}
}

func TestAuthenticateAllowsMissingHeaderToRemainAnonymous(t *testing.T) {
	middleware := NewMiddleware(nil, "", "", localizerStub{}, discardLogger())
	called := false
	handler := middleware.Authenticate(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		called = true
		if identity := FromContext(r.Context()); identity != nil {
			t.Fatalf("missing Authorization header produced identity %+v", identity)
		}
		w.WriteHeader(http.StatusNoContent)
	}))

	response := httptest.NewRecorder()
	handler.ServeHTTP(response, httptest.NewRequest(http.MethodGet, "/public", nil))
	if !called || response.Code != http.StatusNoContent {
		t.Fatalf("anonymous request called=%v status=%d", called, response.Code)
	}
}

func TestAuthenticateRejectsInvalidTokensWithoutLoggingBearerValue(t *testing.T) {
	privateKey := generateTestRSAKey(t)
	wrongPrivateKey := generateTestRSAKey(t)
	const issuer = "https://issuer.example"
	const audience = "stackverse-api"
	keys := &Keys{
		keys:        map[string]*rsa.PublicKey{"known": &privateKey.PublicKey},
		lastRefresh: time.Now(),
		logger:      discardLogger(),
	}
	var output bytes.Buffer
	logger := slog.New(slog.NewJSONHandler(&output, nil))
	middleware := NewMiddleware(keys, issuer, audience, localizerStub{}, logger)

	wrongIssuer := validTestClaims("https://wrong.example", audience)
	missingExpiry := validTestClaims(issuer, audience)
	delete(missingExpiry, "exp")
	missingUsername := validTestClaims(issuer, audience)
	delete(missingUsername, "preferred_username")
	wrongAudience := validTestClaims(issuer, "different-api")

	cases := []struct {
		name   string
		header string
	}{
		{name: "malformed scheme", header: "Basic credential-value"},
		{name: "malformed bearer", header: "Bearer secret-malformed-token"},
		{name: "wrong issuer", header: "Bearer " + signTestToken(t, privateKey, jwt.SigningMethodRS256, "known", wrongIssuer)},
		{name: "wrong audience", header: "Bearer " + signTestToken(t, privateKey, jwt.SigningMethodRS256, "known", wrongAudience)},
		{name: "missing expiration", header: "Bearer " + signTestToken(t, privateKey, jwt.SigningMethodRS256, "known", missingExpiry)},
		{name: "missing username", header: "Bearer " + signTestToken(t, privateKey, jwt.SigningMethodRS256, "known", missingUsername)},
		{name: "disallowed algorithm", header: "Bearer " + signTestToken(t, []byte("test-only-secret"), jwt.SigningMethodHS256, "known", validTestClaims(issuer, audience))},
		{name: "wrong signature", header: "Bearer " + signTestToken(t, wrongPrivateKey, jwt.SigningMethodRS256, "known", validTestClaims(issuer, audience))},
		{name: "unknown kid", header: "Bearer " + signTestToken(t, privateKey, jwt.SigningMethodRS256, "unknown", validTestClaims(issuer, audience))},
	}

	for _, tt := range cases {
		t.Run(tt.name, func(t *testing.T) {
			output.Reset()
			called := false
			handler := middleware.Authenticate(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
				called = true
			}))
			request := httptest.NewRequest(http.MethodGet, "/protected", nil)
			request.Header.Set("Authorization", tt.header)
			response := httptest.NewRecorder()
			handler.ServeHTTP(response, request)

			if called || response.Code != http.StatusUnauthorized {
				t.Fatalf("called=%v status=%d, want rejected 401", called, response.Code)
			}
			if got := response.Header().Get("Content-Type"); got != "application/problem+json" {
				t.Fatalf("content type = %q", got)
			}
			var body map[string]any
			if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil || body["status"] != float64(http.StatusUnauthorized) {
				t.Fatalf("unexpected problem body %q: %v", response.Body.String(), err)
			}
			logged := output.String()
			if !strings.Contains(logged, `"event":"jwt_validation_failed"`) ||
				!strings.Contains(logged, `"error_code":"invalid_token"`) {
				t.Fatalf("missing stable JWT failure fields: %s", logged)
			}
			credential := tt.header
			if _, value, ok := strings.Cut(tt.header, " "); ok {
				credential = value
			}
			if strings.Contains(logged, tt.header) || strings.Contains(logged, credential) {
				t.Fatalf("JWT rejection log leaked bearer material: %s", logged)
			}
		})
	}
}

func TestRequireAuthAndRoleAllowAndDenyAtTheExpectedBoundary(t *testing.T) {
	middleware := NewMiddleware(nil, "", "", localizerStub{}, discardLogger())
	identity := &Identity{Username: "moderator", Roles: []string{"moderator"}}
	request := httptest.NewRequest(http.MethodGet, "/moderator", nil)
	request = request.WithContext(context.WithValue(request.Context(), contextKey{}, identity))

	authenticated := httptest.NewRecorder()
	middleware.RequireAuth(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	})).ServeHTTP(authenticated, request)
	if authenticated.Code != http.StatusNoContent {
		t.Fatalf("authenticated status = %d, want 204", authenticated.Code)
	}

	anonymous := httptest.NewRecorder()
	middleware.RequireRole("moderator")(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	})).ServeHTTP(anonymous, httptest.NewRequest(http.MethodGet, "/moderator", nil))
	if anonymous.Code != http.StatusUnauthorized {
		t.Fatalf("anonymous role check status = %d, want 401", anonymous.Code)
	}
}

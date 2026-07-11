package auth

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/rsa"
	"encoding/base64"
	"encoding/json"
	"log/slog"
	"math/big"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

func TestKeysDiscoversCachesAndRefreshesRotatedSigningKeys(t *testing.T) {
	first := generateRSAKey(t)
	second := generateRSAKey(t)
	var mu sync.Mutex
	current := []map[string]string{
		jwkDocument("first", &first.PublicKey, "sig"),
		{"kty": "EC", "kid": "ignored-ec", "use": "sig"},
		jwkDocument("ignored-encryption", &first.PublicKey, "enc"),
		{"kty": "RSA", "kid": "malformed", "use": "sig", "n": "not-base64", "e": "AQAB"},
	}
	discoveryCalls := 0
	jwksCalls := 0
	var server *httptest.Server
	server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		defer mu.Unlock()
		switch r.URL.Path {
		case "/.well-known/openid-configuration":
			discoveryCalls++
			_ = json.NewEncoder(w).Encode(map[string]string{"jwks_uri": server.URL + "/jwks"})
		case "/jwks":
			jwksCalls++
			_ = json.NewEncoder(w).Encode(map[string]any{"keys": current})
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(server.Close)

	keys := NewKeys(server.URL, "", discardLogger())
	resolved, err := keys.Key(context.Background(), "first")
	if err != nil {
		t.Fatalf("resolve first key: %v", err)
	}
	if resolved.N.Cmp(first.PublicKey.N) != 0 || resolved.E != first.PublicKey.E {
		t.Fatal("resolved RSA key differs from JWKS")
	}
	if _, err := keys.Key(context.Background(), "first"); err != nil {
		t.Fatalf("cached key lookup: %v", err)
	}
	if _, err := keys.Key(context.Background(), "unknown-during-cooldown"); err == nil {
		t.Fatal("unknown key inside refresh cooldown was accepted")
	}
	mu.Lock()
	if discoveryCalls != 1 || jwksCalls != 1 {
		t.Fatalf("cached/unknown lookups fetched IdP again: discovery=%d jwks=%d", discoveryCalls, jwksCalls)
	}
	current = []map[string]string{jwkDocument("second", &second.PublicKey, "sig")}
	mu.Unlock()

	keys.mu.Lock()
	keys.lastRefresh = time.Now().Add(-refreshCooldown)
	keys.mu.Unlock()
	rotated, err := keys.Key(context.Background(), "second")
	if err != nil {
		t.Fatalf("refresh rotated key: %v", err)
	}
	if rotated.N.Cmp(second.PublicKey.N) != 0 {
		t.Fatal("rotated JWKS key was not installed")
	}
	mu.Lock()
	defer mu.Unlock()
	if discoveryCalls != 1 || jwksCalls != 2 {
		t.Fatalf("rotation should reuse discovery URI and refetch JWKS once: discovery=%d jwks=%d", discoveryCalls, jwksCalls)
	}
}

func TestKeysRejectsBrokenIdPDocumentsAndLogsDependencyFailures(t *testing.T) {
	tests := []struct {
		name string
		body string
		code int
		log  bool
	}{
		{name: "non-200", body: `{"error":"down"}`, code: http.StatusServiceUnavailable, log: true},
		{name: "malformed JSON", body: `{`, code: http.StatusOK, log: true},
		{name: "no usable RSA keys", body: `{"keys":[{"kty":"EC","kid":"one"}]}`, code: http.StatusOK},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			logs := &bytes.Buffer{}
			logger := slog.New(slog.NewJSONHandler(logs, nil))
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				w.WriteHeader(test.code)
				_, _ = w.Write([]byte(test.body))
			}))
			t.Cleanup(server.Close)
			keys := NewKeys("https://issuer.invalid", server.URL, logger)
			if _, err := keys.Key(context.Background(), "missing"); err == nil {
				t.Fatal("broken JWKS document was accepted")
			}
			if test.log {
				if !strings.Contains(logs.String(), `"event":"dependency_call_failed"`) ||
					!strings.Contains(logs.String(), `"error_code":"jwks_fetch_failed"`) {
					t.Fatalf("missing dependency failure event: %s", logs.String())
				}
			}
		})
	}

	logs := &bytes.Buffer{}
	logger := slog.New(slog.NewJSONHandler(logs, nil))
	discovery := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]string{})
	}))
	t.Cleanup(discovery.Close)
	if _, err := NewKeys(discovery.URL, "", logger).Key(context.Background(), "missing"); err == nil {
		t.Fatal("discovery document without jwks_uri was accepted")
	}
	if !strings.Contains(logs.String(), `"error_code":"oidc_discovery_failed"`) {
		t.Fatalf("missing discovery dependency failure: %s", logs.String())
	}
}

func TestAuthenticateValidatesJWTClaimsAndNeverLogsRawTokens(t *testing.T) {
	key := generateRSAKey(t)
	const kid = "auth-key"
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]any{"keys": []map[string]string{
			jwkDocument(kid, &key.PublicKey, "sig"),
		}})
	}))
	t.Cleanup(server.Close)
	issuer := "https://issuer.example.test"
	logs := &bytes.Buffer{}
	logger := slog.New(slog.NewJSONHandler(logs, nil))
	middleware := NewMiddleware(NewKeys(issuer, server.URL, logger), issuer, "stackverse-api", localizerStub{}, logger)

	valid := signTestToken(t, key, kid, jwt.MapClaims{
		"iss": issuer, "aud": "stackverse-api", "exp": time.Now().Add(time.Hour).Unix(),
		"preferred_username": "demo", "realm_access": map[string]any{"roles": []string{"moderator"}},
	})
	var identity *Identity
	handler := middleware.Authenticate(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		identity = FromContext(r.Context())
		w.WriteHeader(http.StatusNoContent)
	}))
	validRequest := httptest.NewRequest(http.MethodGet, "/protected", nil)
	validRequest.Header.Set("Authorization", "Bearer "+valid)
	validResponse := httptest.NewRecorder()
	handler.ServeHTTP(validResponse, validRequest)
	if validResponse.Code != http.StatusNoContent || identity == nil || identity.Username != "demo" || !identity.HasRole("moderator") {
		t.Fatalf("valid token result: status=%d identity=%+v", validResponse.Code, identity)
	}

	invalidTokens := []string{
		signTestToken(t, key, kid, jwt.MapClaims{
			"iss": "https://wrong-issuer.test", "aud": "stackverse-api", "exp": time.Now().Add(time.Hour).Unix(),
			"preferred_username": "demo",
		}),
		signTestToken(t, key, kid, jwt.MapClaims{
			"iss": issuer, "aud": "wrong-audience", "exp": time.Now().Add(time.Hour).Unix(),
			"preferred_username": "demo",
		}),
		signTestToken(t, key, kid, jwt.MapClaims{
			"iss": issuer, "aud": "stackverse-api", "exp": time.Now().Add(-time.Minute).Unix(),
			"preferred_username": "demo",
		}),
		signTestToken(t, key, kid, jwt.MapClaims{
			"iss": issuer, "aud": "stackverse-api", "exp": time.Now().Add(time.Hour).Unix(),
		}),
		"not-a-jwt-with-a-secret-payload",
	}
	for _, raw := range invalidTokens {
		called := false
		invalidHandler := middleware.Authenticate(http.HandlerFunc(func(http.ResponseWriter, *http.Request) { called = true }))
		request := httptest.NewRequest(http.MethodGet, "/protected", nil)
		request.Header.Set("Authorization", "Bearer "+raw)
		response := httptest.NewRecorder()
		invalidHandler.ServeHTTP(response, request)
		if response.Code != http.StatusUnauthorized || called {
			t.Fatalf("invalid token status=%d called=%v body=%s", response.Code, called, response.Body.String())
		}
		if strings.Contains(logs.String(), raw) {
			t.Fatalf("raw bearer token leaked into logs: %q", raw)
		}
	}
	if !strings.Contains(logs.String(), `"event":"jwt_validation_failed"`) ||
		!strings.Contains(logs.String(), `"level":"INFO"`) {
		t.Fatalf("invalid-token security signal missing or wrong severity: %s", logs.String())
	}
}

func TestRSAKeyRejectsMalformedBase64Encoding(t *testing.T) {
	if _, err := rsaKey("%%%", "AQAB"); err == nil {
		t.Fatal("malformed modulus was accepted")
	}
	modulus := base64.RawURLEncoding.EncodeToString(big.NewInt(17).Bytes())
	if _, err := rsaKey(modulus, "%%%"); err == nil {
		t.Fatal("malformed exponent was accepted")
	}
}

func generateRSAKey(t *testing.T) *rsa.PrivateKey {
	t.Helper()
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatalf("generate RSA key: %v", err)
	}
	return key
}

func jwkDocument(kid string, key *rsa.PublicKey, use string) map[string]string {
	return map[string]string{
		"kty": "RSA",
		"kid": kid,
		"use": use,
		"n":   base64.RawURLEncoding.EncodeToString(key.N.Bytes()),
		"e":   base64.RawURLEncoding.EncodeToString(big.NewInt(int64(key.E)).Bytes()),
	}
}

func signTestToken(t *testing.T, key *rsa.PrivateKey, kid string, claims jwt.MapClaims) string {
	t.Helper()
	token := jwt.NewWithClaims(jwt.SigningMethodRS256, claims)
	token.Header["kid"] = kid
	signed, err := token.SignedString(key)
	if err != nil {
		t.Fatalf("sign JWT: %v", err)
	}
	return signed
}

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
	"sync/atomic"
	"testing"
	"time"
)

func generateTestRSAKey(t *testing.T) *rsa.PrivateKey {
	t.Helper()
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatalf("generate RSA key: %v", err)
	}
	return key
}

func testJWK(key *rsa.PublicKey, kid string) map[string]string {
	return map[string]string{
		"kty": "RSA",
		"kid": kid,
		"use": "sig",
		"n":   base64.RawURLEncoding.EncodeToString(key.N.Bytes()),
		"e":   base64.RawURLEncoding.EncodeToString(big.NewInt(int64(key.E)).Bytes()),
	}
}

func TestKeysDiscoversCachesAndRefreshesSigningKeys(t *testing.T) {
	firstPrivateKey := generateTestRSAKey(t)
	rotatedPrivateKey := generateTestRSAKey(t)
	var discoveryCalls atomic.Int32
	var jwksCalls atomic.Int32
	var server *httptest.Server
	server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/issuer/.well-known/openid-configuration":
			discoveryCalls.Add(1)
			_ = json.NewEncoder(w).Encode(map[string]string{"jwks_uri": server.URL + "/jwks"})
		case "/jwks":
			call := jwksCalls.Add(1)
			kid := "first"
			if call > 1 {
				kid = "rotated"
			}
			publishedKey := &firstPrivateKey.PublicKey
			if call > 1 {
				publishedKey = &rotatedPrivateKey.PublicKey
			}
			_ = json.NewEncoder(w).Encode(map[string]any{"keys": []any{
				map[string]string{"kty": "EC", "kid": "ignored"},
				map[string]string{"kty": "RSA", "kid": "encryption", "use": "enc", "n": "bad", "e": "bad"},
				map[string]string{"kty": "RSA", "kid": "malformed", "use": "sig", "n": "!", "e": "AQAB"},
				testJWK(publishedKey, kid),
			}})
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()

	keys := NewKeys(server.URL+"/issuer", "", discardLogger())
	first, err := keys.Key(context.Background(), "first")
	if err != nil {
		t.Fatalf("first key lookup: %v", err)
	}
	if first.N.Cmp(firstPrivateKey.N) != 0 || first.E != firstPrivateKey.E {
		t.Fatal("JWKS key does not match the published RSA key")
	}
	if _, err := keys.Key(context.Background(), "first"); err != nil {
		t.Fatalf("cached key lookup: %v", err)
	}
	if discoveryCalls.Load() != 1 || jwksCalls.Load() != 1 {
		t.Fatalf("cached lookup refetched IdP documents: discovery=%d jwks=%d", discoveryCalls.Load(), jwksCalls.Load())
	}

	if _, err := keys.Key(context.Background(), "rotated"); err == nil {
		t.Fatal("unknown kid inside refresh cooldown must be rejected")
	}
	if jwksCalls.Load() != 1 {
		t.Fatalf("cooldown lookup fetched JWKS %d times, want 1", jwksCalls.Load())
	}

	keys.lastRefresh = time.Now().Add(-refreshCooldown)
	rotated, err := keys.Key(context.Background(), "rotated")
	if err != nil {
		t.Fatalf("rotated key lookup after cooldown: %v", err)
	}
	if rotated.N.Cmp(rotatedPrivateKey.N) != 0 || rotated.E != rotatedPrivateKey.E || jwksCalls.Load() != 2 {
		t.Fatalf("rotated lookup did not refresh exactly once: jwks=%d", jwksCalls.Load())
	}
	if discoveryCalls.Load() != 1 {
		t.Fatalf("resolved JWKS URI should be cached, discovery calls=%d", discoveryCalls.Load())
	}
}

func TestKeysUseExplicitJWKSURIAndRejectDocumentsWithoutUsableKeys(t *testing.T) {
	var requestedPath string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requestedPath = r.URL.Path
		_ = json.NewEncoder(w).Encode(map[string]any{"keys": []any{
			map[string]string{"kty": "EC", "kid": "not-rsa"},
			map[string]string{"kty": "RSA", "kid": "bad", "use": "sig", "n": "!", "e": "!"},
		}})
	}))
	defer server.Close()

	keys := NewKeys("http://issuer.invalid", server.URL+"/configured-jwks", discardLogger())
	_, err := keys.Key(context.Background(), "missing")
	if err == nil || !strings.Contains(err.Error(), "no usable RSA signing keys") {
		t.Fatalf("unusable JWKS error = %v", err)
	}
	if requestedPath != "/configured-jwks" {
		t.Fatalf("requested path = %q, want explicit JWKS URI", requestedPath)
	}
}

func TestKeysLogStableDependencyFailureWithoutCredentials(t *testing.T) {
	var output bytes.Buffer
	logger := slog.New(slog.NewJSONHandler(&output, nil))
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
	}))
	defer server.Close()

	keys := NewKeys("http://issuer.invalid", server.URL+"/jwks", logger)
	if _, err := keys.Key(context.Background(), "kid"); err == nil {
		t.Fatal("non-200 JWKS response must fail")
	}
	logged := output.String()
	if !strings.Contains(logged, `"event":"dependency_call_failed"`) ||
		!strings.Contains(logged, `"error_code":"jwks_fetch_failed"`) {
		t.Fatalf("dependency failure log missing stable fields: %s", logged)
	}
	if strings.Contains(logged, "Authorization") {
		t.Fatalf("dependency log leaked credential material: %s", logged)
	}
}

func TestDiscoveryRequiresJWKSURIAndGetJSONRejectsMalformedPayload(t *testing.T) {
	var output bytes.Buffer
	logger := slog.New(slog.NewJSONHandler(&output, nil))
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.Contains(r.URL.Path, "openid-configuration") {
			_, _ = w.Write([]byte(`{"issuer":"present-but-no-jwks"}`))
			return
		}
		_, _ = w.Write([]byte(`{"broken":`))
	}))
	defer server.Close()

	keys := NewKeys(server.URL, "", logger)
	if _, err := keys.discoverJWKSURI(context.Background()); err == nil {
		t.Fatal("discovery without jwks_uri must fail")
	}
	if !strings.Contains(output.String(), `"error_code":"oidc_discovery_failed"`) {
		t.Fatalf("discovery failure missing stable log code: %s", output.String())
	}

	var target map[string]any
	if err := keys.getJSON(context.Background(), server.URL+"/malformed", &target); err == nil {
		t.Fatal("malformed JSON response must fail")
	}
	if err := keys.getJSON(context.Background(), "://invalid", &target); err == nil {
		t.Fatal("invalid request URI must fail")
	}
}

func TestRSAKeyRejectsInvalidBase64Components(t *testing.T) {
	if _, err := rsaKey("!", "AQAB"); err == nil {
		t.Fatal("invalid modulus encoding must fail")
	}
	validModulus := base64.RawURLEncoding.EncodeToString([]byte{1})
	if _, err := rsaKey(validModulus, "!"); err == nil {
		t.Fatal("invalid exponent encoding must fail")
	}
}

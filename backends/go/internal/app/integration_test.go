package app

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/rsa"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"math/big"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/kamkie/stackverse/backends/go/internal/config"
	"github.com/kamkie/stackverse/backends/go/internal/messages"
	"github.com/kamkie/stackverse/backends/go/internal/store"
)

const integrationDatabaseEnv = "STACKVERSE_GO_TEST_DATABASE_URL"

var integrationPool *pgxpool.Pool

func TestMain(m *testing.M) {
	dsn := os.Getenv(integrationDatabaseEnv)
	if dsn == "" {
		if os.Getenv("CI") == "true" {
			_, _ = fmt.Fprintf(os.Stderr, "%s is required in CI\n", integrationDatabaseEnv)
			os.Exit(1)
		}
		os.Exit(m.Run())
	}

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	logger := slog.New(slog.NewJSONHandler(io.Discard, nil))
	pool, err := store.Open(ctx, dsn, logger)
	cancel()
	if err != nil {
		_, _ = fmt.Fprintf(os.Stderr, "open integration database: %v\n", err)
		os.Exit(1)
	}
	integrationPool = pool
	code := m.Run()
	pool.Close()
	os.Exit(code)
}

type integrationHarness struct {
	handler http.Handler
	pool    *pgxpool.Pool
	cfg     config.Config
	key     *rsa.PrivateKey
	kid     string
	logger  *slog.Logger
	logs    *bytes.Buffer
	jwks    *httptest.Server
}

func newIntegrationHarness(t *testing.T) *integrationHarness {
	t.Helper()
	if integrationPool == nil {
		t.Skipf("set %s to run PostgreSQL-backed integration tests", integrationDatabaseEnv)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	t.Cleanup(cancel)
	if _, err := integrationPool.Exec(ctx,
		"truncate table audit_entries, reports, bookmarks, messages, user_accounts cascade"); err != nil {
		t.Fatalf("reset integration database: %v", err)
	}

	logs := &bytes.Buffer{}
	logger := slog.New(slog.NewJSONHandler(logs, nil))
	messageStore := messages.NewStore(integrationPool)
	if err := messageStore.Seed(ctx, contractMessageSeedDir(t), logger); err != nil {
		t.Fatalf("seed contract messages: %v", err)
	}

	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatalf("generate integration signing key: %v", err)
	}
	h := &integrationHarness{
		pool: integrationPool, key: key, kid: "integration-key", logger: logger, logs: logs,
	}
	h.jwks = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/jwks" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"keys": []map[string]string{{
			"kty": "RSA",
			"kid": h.kid,
			"use": "sig",
			"n":   base64.RawURLEncoding.EncodeToString(key.PublicKey.N.Bytes()),
			"e":   base64.RawURLEncoding.EncodeToString(big.NewInt(int64(key.PublicKey.E)).Bytes()),
		}}})
	}))
	t.Cleanup(h.jwks.Close)
	h.cfg = config.Config{IssuerURI: h.jwks.URL, JWKSURI: h.jwks.URL + "/jwks"}
	h.handler, _ = New(h.cfg, integrationPool, logger)
	return h
}

func contractMessageSeedDir(t *testing.T) string {
	t.Helper()
	_, currentFile, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("resolve integration test source path")
	}
	return filepath.Clean(filepath.Join(filepath.Dir(currentFile), "..", "..", "..", "..", "spec", "messages"))
}

func (h *integrationHarness) token(t *testing.T, username string, roles ...string) string {
	t.Helper()
	now := time.Now()
	token := jwt.NewWithClaims(jwt.SigningMethodRS256, jwt.MapClaims{
		"iss":                h.cfg.IssuerURI,
		"aud":                config.Audience,
		"exp":                now.Add(time.Hour).Unix(),
		"iat":                now.Add(-time.Minute).Unix(),
		"preferred_username": username,
		"name":               "Test " + username,
		"email":              username + "@example.test",
		"realm_access":       map[string]any{"roles": roles},
	})
	token.Header["kid"] = h.kid
	signed, err := token.SignedString(h.key)
	if err != nil {
		t.Fatalf("sign token for %s: %v", username, err)
	}
	return signed
}

type apiResponse struct {
	Status int
	Header http.Header
	Body   []byte
}

func (h *integrationHarness) do(t *testing.T, method, path, token string, body any) apiResponse {
	t.Helper()
	return h.doOn(t, h.handler, method, path, token, body)
}

func (h *integrationHarness) doOn(t *testing.T, handler http.Handler, method, path, token string, body any) apiResponse {
	t.Helper()
	return h.doRaw(t, handler, method, path, token, marshalRequestBody(t, body))
}

func (h *integrationHarness) doWithHeader(t *testing.T, method, path, token string, body any, name, value string) apiResponse {
	t.Helper()
	header := http.Header{}
	header.Set(name, value)
	return h.doRaw(t, h.handler, method, path, token, marshalRequestBody(t, body), header)
}

func marshalRequestBody(t *testing.T, body any) []byte {
	t.Helper()
	var raw []byte
	if body != nil {
		var err error
		raw, err = json.Marshal(body)
		if err != nil {
			t.Fatalf("marshal request body: %v", err)
		}
	}
	return raw
}

func (h *integrationHarness) doRaw(t *testing.T, handler http.Handler, method, path, token string, body []byte, headers ...http.Header) apiResponse {
	t.Helper()
	request := httptest.NewRequest(method, path, bytes.NewReader(body))
	if body != nil {
		request.Header.Set("Content-Type", "application/json")
	}
	if token != "" {
		request.Header.Set("Authorization", "Bearer "+token)
	}
	for _, header := range headers {
		for name, values := range header {
			request.Header[name] = append([]string(nil), values...)
		}
	}
	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, request)
	result := recorder.Result()
	defer result.Body.Close()
	responseBody, err := io.ReadAll(result.Body)
	if err != nil {
		t.Fatalf("read response body: %v", err)
	}
	return apiResponse{Status: result.StatusCode, Header: result.Header.Clone(), Body: responseBody}
}

func requireStatus(t *testing.T, response apiResponse, expected int) {
	t.Helper()
	if response.Status != expected {
		t.Fatalf("status = %d, want %d; body=%s", response.Status, expected, response.Body)
	}
}

func decodeResponse[T any](t *testing.T, response apiResponse) T {
	t.Helper()
	var body T
	if err := json.Unmarshal(response.Body, &body); err != nil {
		t.Fatalf("decode response %q: %v", response.Body, err)
	}
	return body
}

type pageDocument[T any] struct {
	Items      []T   `json:"items"`
	Page       int   `json:"page"`
	Size       int   `json:"size"`
	TotalItems int64 `json:"totalItems"`
	TotalPages int   `json:"totalPages"`
}

type bookmarkDocument struct {
	ID         string   `json:"id"`
	URL        string   `json:"url"`
	Title      string   `json:"title"`
	Notes      *string  `json:"notes"`
	Tags       []string `json:"tags"`
	Visibility string   `json:"visibility"`
	Status     string   `json:"status"`
	Owner      string   `json:"owner"`
}

type cursorPageDocument struct {
	Items      []bookmarkDocument `json:"items"`
	NextCursor *string            `json:"nextCursor"`
}

type reportDocument struct {
	ID             string  `json:"id"`
	BookmarkID     string  `json:"bookmarkId"`
	Reporter       string  `json:"reporter"`
	Reason         string  `json:"reason"`
	Comment        *string `json:"comment"`
	Status         string  `json:"status"`
	ResolvedBy     *string `json:"resolvedBy"`
	ResolutionNote *string `json:"resolutionNote"`
}

type messageDocument struct {
	ID          string  `json:"id"`
	Key         string  `json:"key"`
	Language    string  `json:"language"`
	Text        string  `json:"text"`
	Description *string `json:"description"`
}

type problemDocument struct {
	Title  string `json:"title"`
	Status int    `json:"status"`
	Detail string `json:"detail"`
	Errors []struct {
		Field      string `json:"field"`
		MessageKey string `json:"messageKey"`
		Message    string `json:"message"`
	} `json:"errors"`
}

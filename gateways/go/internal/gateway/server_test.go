package gateway

import (
	"context"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"math/big"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/kamkie/stackverse/gateways/go/internal/config"
	"github.com/kamkie/stackverse/gateways/go/internal/session"
)

func TestSessionEndpointReportsUnauthenticatedWithoutSession(t *testing.T) {
	app := newHarness(t, nil)
	response := app.get("/auth/session")

	if response.StatusCode != http.StatusOK {
		t.Fatalf("status = %d", response.StatusCode)
	}
	var body map[string]any
	decodeJSON(t, response, &body)
	if body["authenticated"] != false {
		t.Fatalf("authenticated = %#v", body["authenticated"])
	}
	if _, ok := body["username"]; ok {
		t.Fatalf("username should be absent")
	}
}

func TestAnonymousAPIRequestsRelayWithoutBearerToken(t *testing.T) {
	app := newHarness(t, nil)
	request, _ := http.NewRequest(http.MethodGet, app.server.URL+"/api/v2/bookmarks?visibility=public", nil)
	request.Header.Set("Authorization", "Bearer forged")
	request.AddCookie(&http.Cookie{Name: "unrelated", Value: "cookie"})

	response, err := app.client.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()

	if response.StatusCode != http.StatusOK {
		t.Fatalf("status = %d", response.StatusCode)
	}
	if app.backend.lastAuthorization != "" {
		t.Fatalf("Authorization leaked upstream: %q", app.backend.lastAuthorization)
	}
	if app.backend.lastCookie != "" {
		t.Fatalf("Cookie leaked upstream: %q", app.backend.lastCookie)
	}
}

func TestAuthenticatedAPIRequestsRelayBearerToken(t *testing.T) {
	app := newHarness(t, nil)
	app.putSession("s1", session.Data{
		Username:     "demo",
		AccessToken:  "live-access",
		RefreshToken: "refresh",
		ExpiresAt:    time.Now().Add(time.Hour),
	})
	app.setSessionCookie("s1")

	request, _ := http.NewRequest(http.MethodGet, app.server.URL+"/api/v1/me", nil)
	request.Header.Set("Authorization", "Bearer forged")
	response, err := app.client.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()

	if response.StatusCode != http.StatusOK {
		t.Fatalf("status = %d", response.StatusCode)
	}
	if app.backend.lastAuthorization != "Bearer live-access" {
		t.Fatalf("Authorization = %q", app.backend.lastAuthorization)
	}
	if app.backend.lastCookie != "" {
		t.Fatalf("Cookie leaked upstream: %q", app.backend.lastCookie)
	}
}

func TestStateChangingAPIRequiresCSRFAndSameOriginSignals(t *testing.T) {
	app := newHarness(t, nil)

	missing := app.post("/api/v1/bookmarks", "", nil)
	if missing.StatusCode != http.StatusForbidden {
		t.Fatalf("missing CSRF status = %d", missing.StatusCode)
	}
	if got := missing.Header.Get("Content-Type"); !strings.HasPrefix(got, "application/problem+json") {
		t.Fatalf("problem content type = %q", got)
	}

	xsrf := app.issueXSRF()
	allowed := app.post("/api/v1/bookmarks", xsrf, map[string]string{"Origin": "http://localhost:8000"})
	if allowed.StatusCode != http.StatusOK {
		t.Fatalf("same-origin status = %d", allowed.StatusCode)
	}
	if app.backend.lastCSRFHeader != "" {
		t.Fatalf("CSRF header leaked upstream: %q", app.backend.lastCSRFHeader)
	}

	rejected := app.post("/api/v1/bookmarks", xsrf, map[string]string{"Sec-Fetch-Site": "same-site"})
	if rejected.StatusCode != http.StatusForbidden {
		t.Fatalf("same-site status = %d", rejected.StatusCode)
	}
	body, _ := io.ReadAll(rejected.Body)
	if !strings.Contains(string(body), "Cross-origin state-changing requests are not supported.") {
		t.Fatalf("unexpected problem: %s", body)
	}
}

func TestAPIUpstreamFailureReturnsProblemDocument(t *testing.T) {
	app := newHarnessWithConfig(t, nil, harnessConfig{
		transport: roundTripFunc(func(*http.Request) (*http.Response, error) {
			return nil, errors.New("backend unavailable")
		}),
	})

	response := app.get("/api/v1/bookmarks")
	defer response.Body.Close()

	if response.StatusCode != http.StatusBadGateway {
		t.Fatalf("status = %d", response.StatusCode)
	}
	assertAPIHeaders(t, response, false)
	if got := response.Header.Get("Content-Type"); !strings.HasPrefix(got, "application/problem+json") {
		t.Fatalf("content type = %q", got)
	}
	body, _ := io.ReadAll(response.Body)
	if !strings.Contains(string(body), "The upstream API is temporarily unavailable.") {
		t.Fatalf("problem body = %s", body)
	}
}

func TestSecurityHeadersAreScopedWithoutChangingAPISemantics(t *testing.T) {
	app := newHarness(t, nil)

	sessionResponse := app.get("/auth/session")
	assertDocumentHeaders(t, sessionResponse, false)

	api := app.get("/api/v1/messages/bundle")
	assertAPIHeaders(t, api, false)
	if got := api.Header.Get("Cache-Control"); got != "no-cache" {
		t.Fatalf("Cache-Control = %q", got)
	}
	if got := api.Header.Get("ETag"); got != `"bundle-v1"` {
		t.Fatalf("ETag = %q", got)
	}
}

func TestFrontendProxyUsesConfiguredUpstreamAndStripsCookies(t *testing.T) {
	var upstreamPath string
	var upstreamCookie string
	frontend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		upstreamPath = r.URL.RequestURI()
		upstreamCookie = r.Header.Get("Cookie")
		w.Header().Set("Content-Type", "text/plain")
		_, _ = w.Write([]byte("frontend ok"))
	}))
	t.Cleanup(frontend.Close)
	app := newHarnessWithConfig(t, nil, harnessConfig{frontendURL: mustURL(t, frontend.URL)})

	request, _ := http.NewRequest(http.MethodGet, app.server.URL+"/deep/link?tab=all", nil)
	request.AddCookie(&http.Cookie{Name: "browser_state", Value: "private"})
	response, err := app.client.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()

	if response.StatusCode != http.StatusOK {
		t.Fatalf("status = %d", response.StatusCode)
	}
	assertDocumentHeaders(t, response, false)
	if upstreamPath != "/deep/link?tab=all" {
		t.Fatalf("upstream path = %q", upstreamPath)
	}
	if upstreamCookie != "" {
		t.Fatalf("Cookie leaked to frontend upstream: %q", upstreamCookie)
	}
	body, _ := io.ReadAll(response.Body)
	if string(body) != "frontend ok" {
		t.Fatalf("body = %q", body)
	}
}

func TestSPAServesBundledIndexAndRejectsWrites(t *testing.T) {
	app := newHarness(t, nil)

	response := app.get("/")
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		t.Fatalf("GET status = %d", response.StatusCode)
	}
	assertDocumentHeaders(t, response, false)
	body, _ := io.ReadAll(response.Body)
	if !strings.Contains(string(body), "Stackverse gateway placeholder") {
		t.Fatalf("fallback body = %s", body)
	}

	request, _ := http.NewRequest(http.MethodPost, app.server.URL+"/", nil)
	rejected, err := app.client.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer rejected.Body.Close()
	if rejected.StatusCode != http.StatusNotFound {
		t.Fatalf("POST status = %d", rejected.StatusCode)
	}
}

func TestLoginRedirectsWithCodeFlowAndPKCE(t *testing.T) {
	app := newHarness(t, nil)
	response := app.get("/auth/login")
	if response.StatusCode != http.StatusFound {
		t.Fatalf("status = %d", response.StatusCode)
	}
	location, err := url.Parse(response.Header.Get("Location"))
	if err != nil {
		t.Fatal(err)
	}
	if location.Path != "/realms/stackverse/protocol/openid-connect/auth" {
		t.Fatalf("auth path = %s", location.Path)
	}
	query := location.Query()
	if query.Get("response_type") != "code" {
		t.Fatalf("response_type = %q", query.Get("response_type"))
	}
	if query.Get("code_challenge_method") != "S256" || query.Get("code_challenge") == "" {
		t.Fatalf("PKCE parameters missing: %s", location.RawQuery)
	}
	if query.Get("redirect_uri") != "http://localhost:8000/auth/callback" {
		t.Fatalf("redirect_uri = %q", query.Get("redirect_uri"))
	}
}

func TestFailedCallbackRedirectsHomeWithoutSession(t *testing.T) {
	app := newHarness(t, nil)
	response := app.get("/auth/callback?error=access_denied&state=whatever")

	if response.StatusCode != http.StatusFound {
		t.Fatalf("status = %d", response.StatusCode)
	}
	if response.Header.Get("Location") != "/" {
		t.Fatalf("location = %q", response.Header.Get("Location"))
	}
	if cookie := findSetCookie(response, sessionCookieName); cookie != nil {
		t.Fatalf("session cookie should not be set: %#v", cookie)
	}
}

func TestCallbackRequiresLoginStateCookie(t *testing.T) {
	app := newHarness(t, nil)

	login := app.get("/auth/login")
	state := mustParseLocation(t, login).Query().Get("state")
	jar, _ := cookiejar.New(nil)
	app.client.Jar = jar

	callback := app.get("/auth/callback?code=ok&state=" + url.QueryEscape(state))
	if callback.StatusCode != http.StatusFound {
		t.Fatalf("callback status = %d", callback.StatusCode)
	}
	if cookie := findSetCookie(callback, sessionCookieName); cookie != nil {
		t.Fatalf("session cookie should not be set: %#v", cookie)
	}

	sessionResponse := app.get("/auth/session")
	var body map[string]any
	decodeJSON(t, sessionResponse, &body)
	if body["authenticated"] != false {
		t.Fatalf("session body = %#v", body)
	}
}

func TestSuccessfulCallbackCreatesRedisBackedSession(t *testing.T) {
	app := newHarness(t, nil)

	login := app.get("/auth/login")
	state := mustParseLocation(t, login).Query().Get("state")
	callback := app.get("/auth/callback?code=ok&state=" + url.QueryEscape(state))
	if callback.StatusCode != http.StatusFound {
		t.Fatalf("callback status = %d", callback.StatusCode)
	}
	if callback.Header.Get("Location") != "/" {
		t.Fatalf("callback location = %q", callback.Header.Get("Location"))
	}
	if cookie := findSetCookie(callback, sessionCookieName); cookie == nil || cookie.Value == "" {
		t.Fatalf("session cookie missing")
	}

	sessionResponse := app.get("/auth/session")
	var body map[string]any
	decodeJSON(t, sessionResponse, &body)
	if body["authenticated"] != true || body["username"] != "demo" {
		t.Fatalf("session body = %#v", body)
	}
}

func TestRefreshRejectedDestroysSessionAndRelaysAnonymous(t *testing.T) {
	idp := &idpBehavior{refreshStatus: http.StatusBadRequest}
	app := newHarness(t, idp)
	app.putSession("s1", expiredSession())
	app.setSessionCookie("s1")

	response := app.get("/api/v1/bookmarks")
	if response.StatusCode != http.StatusOK {
		t.Fatalf("status = %d", response.StatusCode)
	}
	if app.backend.lastAuthorization != "" {
		t.Fatalf("expected anonymous relay, got %q", app.backend.lastAuthorization)
	}
	if _, ok := app.store.sessions["s1"]; ok {
		t.Fatalf("session should be destroyed")
	}
	if cookie := findSetCookie(response, sessionCookieName); cookie == nil || cookie.MaxAge >= 0 {
		t.Fatalf("session clearing cookie missing: %#v", cookie)
	}
}

func TestIDPOutageDuringRefreshKeepsSessionAndReturns503(t *testing.T) {
	idp := &idpBehavior{refreshStatus: http.StatusServiceUnavailable}
	app := newHarness(t, idp)
	app.putSession("s1", expiredSession())
	app.setSessionCookie("s1")

	response := app.get("/api/v1/bookmarks")
	if response.StatusCode != http.StatusServiceUnavailable {
		t.Fatalf("status = %d", response.StatusCode)
	}
	if _, ok := app.store.sessions["s1"]; !ok {
		t.Fatalf("session should survive IdP outage")
	}
}

func TestRefreshSuccessUpdatesSessionAndRelaysNewBearer(t *testing.T) {
	idp := &idpBehavior{refreshStatus: http.StatusOK}
	app := newHarness(t, idp)
	app.putSession("s1", expiredSession())
	app.setSessionCookie("s1")

	response := app.get("/api/v1/bookmarks")
	if response.StatusCode != http.StatusOK {
		t.Fatalf("status = %d", response.StatusCode)
	}
	if app.backend.lastAuthorization != "Bearer refreshed-access" {
		t.Fatalf("Authorization = %q", app.backend.lastAuthorization)
	}
	if got := app.store.sessions["s1"].RefreshToken; got != "refreshed-refresh" {
		t.Fatalf("refresh token = %q", got)
	}
}

func TestLogoutDestroysLocalSessionAndReturns204(t *testing.T) {
	app := newHarness(t, nil)
	app.putSession("s1", session.Data{
		Username:     "demo",
		AccessToken:  "access",
		RefreshToken: "refresh",
		ExpiresAt:    time.Now().Add(time.Hour),
	})
	app.setSessionCookie("s1")

	request, _ := http.NewRequest(http.MethodPost, app.server.URL+"/auth/logout", nil)
	response, err := app.client.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()

	if response.StatusCode != http.StatusNoContent {
		t.Fatalf("status = %d", response.StatusCode)
	}
	if _, ok := app.store.sessions["s1"]; ok {
		t.Fatalf("session should be destroyed")
	}
	if app.idp.logoutCalls != 1 {
		t.Fatalf("logout calls = %d", app.idp.logoutCalls)
	}
}

func TestSessionStoreFailureDoesNotExposeSessionOrReachBackend(t *testing.T) {
	app := newHarness(t, nil)
	app.setSessionCookie("unavailable")
	app.store.loadErr = errors.New("redis password=do-not-log")

	sessionResponse := app.get("/auth/session")
	var sessionBody map[string]any
	decodeJSON(t, sessionResponse, &sessionBody)
	if sessionBody["authenticated"] != false {
		t.Fatalf("session body = %#v", sessionBody)
	}

	apiResponse := app.get("/api/v1/bookmarks")
	defer apiResponse.Body.Close()
	if apiResponse.StatusCode != http.StatusServiceUnavailable {
		t.Fatalf("API status = %d", apiResponse.StatusCode)
	}
	assertAPIHeaders(t, apiResponse, false)
	if app.backend.requests != 0 {
		t.Fatalf("backend requests = %d, want 0", app.backend.requests)
	}
}

func TestRefreshPersistenceFailureKeepsOldSessionAndReturns503(t *testing.T) {
	app := newHarness(t, &idpBehavior{refreshStatus: http.StatusOK})
	app.putSession("s1", expiredSession())
	app.setSessionCookie("s1")
	app.store.saveErr = errors.New("redis unavailable")

	response := app.get("/api/v1/bookmarks")
	defer response.Body.Close()
	if response.StatusCode != http.StatusServiceUnavailable {
		t.Fatalf("status = %d", response.StatusCode)
	}
	if app.backend.requests != 0 {
		t.Fatalf("backend requests = %d, want 0", app.backend.requests)
	}
	if got := app.store.sessions["s1"].AccessToken; got != "old-access" {
		t.Fatalf("stored access token = %q", got)
	}
}

func TestCallbackStateIsSingleUse(t *testing.T) {
	app := newHarness(t, nil)
	login := app.get("/auth/login")
	state := mustParseLocation(t, login).Query().Get("state")

	first := app.get("/auth/callback?code=ok&state=" + url.QueryEscape(state))
	if findSetCookie(first, sessionCookieName) == nil {
		t.Fatal("first callback did not create a session")
	}
	first.Body.Close()

	request, _ := http.NewRequest(http.MethodGet, app.server.URL+"/auth/callback?code=ok&state="+url.QueryEscape(state), nil)
	request.AddCookie(&http.Cookie{Name: loginStateCookieName, Value: state})
	second, err := app.client.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer second.Body.Close()
	if findSetCookie(second, sessionCookieName) != nil {
		t.Fatal("replayed callback created a session")
	}
}

func TestLogoutRemainsLocalFirstWhenStoreOperationsFail(t *testing.T) {
	app := newHarness(t, nil)
	app.setSessionCookie("s1")
	app.store.loadErr = errors.New("redis read failed")
	app.store.deleteErr = errors.New("redis delete failed")

	request, _ := http.NewRequest(http.MethodPost, app.server.URL+"/auth/logout", nil)
	response, err := app.client.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusNoContent {
		t.Fatalf("status = %d", response.StatusCode)
	}
	if cookie := findSetCookie(response, sessionCookieName); cookie == nil || cookie.MaxAge >= 0 {
		t.Fatalf("session clearing cookie missing: %#v", cookie)
	}
	if app.idp.logoutCalls != 0 {
		t.Fatalf("IdP logout calls = %d", app.idp.logoutCalls)
	}
}

type harness struct {
	t       *testing.T
	server  *httptest.Server
	client  *http.Client
	backend *backendRecorder
	idp     *idpBehavior
	store   *memoryStore
}

type harnessConfig struct {
	backendHandler http.Handler
	frontendURL    *url.URL
	publicURL      *url.URL
	transport      http.RoundTripper
}

func newHarness(t *testing.T, behavior *idpBehavior) *harness {
	t.Helper()
	return newHarnessWithConfig(t, behavior, harnessConfig{})
}

func newHarnessWithConfig(t *testing.T, behavior *idpBehavior, overrides harnessConfig) *harness {
	t.Helper()
	if behavior == nil {
		behavior = &idpBehavior{refreshStatus: http.StatusOK}
	}
	idpServer := newIDPServer(t, behavior)
	t.Cleanup(idpServer.Close)

	backend := &backendRecorder{}
	backendHandler := http.Handler(backend)
	if overrides.backendHandler != nil {
		backendHandler = overrides.backendHandler
	}
	backendServer := httptest.NewServer(backendHandler)
	t.Cleanup(backendServer.Close)

	publicURL := overrides.publicURL
	if publicURL == nil {
		publicURL = mustURL(t, "http://localhost:8000")
	}
	transport := overrides.transport
	if transport == nil {
		transport = http.DefaultTransport
	}
	cfg := config.Config{
		Port:                  "0",
		BackendURL:            mustURL(t, backendServer.URL),
		FrontendURL:           overrides.frontendURL,
		RedisURL:              "redis://localhost:6379",
		OIDCIssuerURI:         idpServer.URL + "/realms/stackverse",
		OIDCInternalIssuerURI: idpServer.URL + "/realms/stackverse",
		OIDCClientID:          "stackverse-gateway",
		OIDCClientSecret:      "stackverse-secret",
		PublicURL:             publicURL,
		LogLevel:              "error",
		LogFormat:             "text",
	}
	store := newMemoryStore()
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	handler, err := NewHandler(cfg, store, logger, idpServer.Client(), transport)
	if err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(handler)
	t.Cleanup(server.Close)

	jar, _ := cookiejar.New(nil)
	client := server.Client()
	client.Jar = jar
	client.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }

	return &harness{
		t:       t,
		server:  server,
		client:  client,
		backend: backend,
		idp:     behavior,
		store:   store,
	}
}

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(request *http.Request) (*http.Response, error) {
	return f(request)
}

func (h *harness) get(path string) *http.Response {
	h.t.Helper()
	response, err := h.client.Get(h.server.URL + path)
	if err != nil {
		h.t.Fatal(err)
	}
	return response
}

func (h *harness) post(path, xsrf string, headers map[string]string) *http.Response {
	h.t.Helper()
	request, _ := http.NewRequest(http.MethodPost, h.server.URL+path, strings.NewReader(`{"url":"https://example.com"}`))
	request.Header.Set("Content-Type", "application/json")
	if xsrf != "" {
		request.Header.Set(csrfHeaderName, xsrf)
	}
	for name, value := range headers {
		request.Header.Set(name, value)
	}
	response, err := h.client.Do(request)
	if err != nil {
		h.t.Fatal(err)
	}
	return response
}

func (h *harness) issueXSRF() string {
	h.t.Helper()
	response := h.get("/auth/session")
	defer response.Body.Close()
	cookie := findSetCookie(response, csrfCookieName)
	if cookie != nil {
		return cookie.Value
	}
	base, _ := url.Parse(h.server.URL)
	for _, cookie := range h.client.Jar.Cookies(base) {
		if cookie.Name == csrfCookieName {
			return cookie.Value
		}
	}
	h.t.Fatal("XSRF-TOKEN missing")
	return ""
}

func (h *harness) putSession(key string, data session.Data) {
	h.t.Helper()
	if data.CreatedAt.IsZero() {
		data.CreatedAt = time.Now().UTC()
	}
	if data.UpdatedAt.IsZero() {
		data.UpdatedAt = data.CreatedAt
	}
	h.store.sessions[key] = data
}

func (h *harness) setSessionCookie(value string) {
	h.t.Helper()
	base, _ := url.Parse(h.server.URL)
	h.client.Jar.SetCookies(base, []*http.Cookie{{Name: sessionCookieName, Value: value, Path: "/"}})
}

type backendRecorder struct {
	lastAuthorization string
	lastCookie        string
	lastCSRFHeader    string
	requests          int
}

func (b *backendRecorder) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	b.requests++
	b.lastAuthorization = r.Header.Get("Authorization")
	b.lastCookie = r.Header.Get("Cookie")
	b.lastCSRFHeader = r.Header.Get(csrfHeaderName)
	w.Header().Set("X-Content-Type-Options", "nosniff")
	if r.URL.Path == "/api/v1/messages/bundle" {
		w.Header().Set("Cache-Control", "no-cache")
		w.Header().Set("ETag", `"bundle-v1"`)
	}
	w.Header().Set("Content-Type", "application/json")
	_, _ = w.Write([]byte(`{"ok":true}`))
}

type idpBehavior struct {
	refreshStatus int
	logoutCalls   int
}

func newIDPServer(t *testing.T, behavior *idpBehavior) *httptest.Server {
	t.Helper()
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	var issuer string
	mux := http.NewServeMux()
	server := httptest.NewServer(mux)
	issuer = server.URL + "/realms/stackverse"

	mux.HandleFunc("/realms/stackverse/protocol/openid-connect/certs", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(t, w, map[string]any{"keys": []any{jwk(key)}})
	})
	mux.HandleFunc("/realms/stackverse/protocol/openid-connect/token", func(w http.ResponseWriter, r *http.Request) {
		if err := r.ParseForm(); err != nil {
			t.Error(err)
		}
		if r.Form.Get("grant_type") == "authorization_code" && r.Form.Get("code_verifier") == "" {
			t.Error("code_verifier missing")
		}
		if r.Form.Get("grant_type") == "refresh_token" {
			status := behavior.refreshStatus
			if status == 0 {
				status = http.StatusOK
			}
			if status != http.StatusOK {
				w.WriteHeader(status)
				_, _ = w.Write([]byte(`{"error":"temporary"}`))
				return
			}
			writeJSON(t, w, map[string]any{
				"access_token":  "refreshed-access",
				"refresh_token": "refreshed-refresh",
				"expires_in":    300,
			})
			return
		}
		writeJSON(t, w, map[string]any{
			"access_token":  "access",
			"refresh_token": "refresh",
			"expires_in":    300,
			"id_token":      signedIDToken(t, key, issuer, "stackverse-gateway", "demo"),
			"token_type":    "Bearer",
		})
	})
	mux.HandleFunc("/realms/stackverse/protocol/openid-connect/logout", func(w http.ResponseWriter, r *http.Request) {
		behavior.logoutCalls++
		w.WriteHeader(http.StatusNoContent)
	})
	return server
}

func signedIDToken(t *testing.T, key *rsa.PrivateKey, issuer, audience, username string) string {
	t.Helper()
	header := base64JSON(t, map[string]any{"alg": "RS256", "kid": "test-key", "typ": "JWT"})
	claims := base64JSON(t, map[string]any{
		"iss":                issuer,
		"aud":                audience,
		"sub":                "subject-demo",
		"preferred_username": username,
		"iat":                time.Now().Unix(),
		"exp":                time.Now().Add(5 * time.Minute).Unix(),
	})
	signingInput := header + "." + claims
	sum := sha256.Sum256([]byte(signingInput))
	signature, err := rsa.SignPKCS1v15(rand.Reader, key, crypto.SHA256, sum[:])
	if err != nil {
		t.Fatal(err)
	}
	return signingInput + "." + base64.RawURLEncoding.EncodeToString(signature)
}

func jwk(key *rsa.PrivateKey) map[string]any {
	return map[string]any{
		"kty": "RSA",
		"use": "sig",
		"kid": "test-key",
		"alg": "RS256",
		"n":   base64.RawURLEncoding.EncodeToString(key.PublicKey.N.Bytes()),
		"e":   base64.RawURLEncoding.EncodeToString(big.NewInt(int64(key.PublicKey.E)).Bytes()),
	}
}

type memoryStore struct {
	mu        sync.Mutex
	sessions  map[string]session.Data
	states    map[string]session.OAuthState
	loadErr   error
	saveErr   error
	deleteErr error
}

func newMemoryStore() *memoryStore {
	return &memoryStore{sessions: map[string]session.Data{}, states: map[string]session.OAuthState{}}
}

func (s *memoryStore) LoadSession(_ context.Context, key string) (session.Data, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.loadErr != nil {
		return session.Data{}, false, s.loadErr
	}
	data, ok := s.sessions[key]
	return data, ok, nil
}

func (s *memoryStore) SaveSession(_ context.Context, key string, data session.Data, _ time.Duration) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.saveErr != nil {
		return s.saveErr
	}
	s.sessions[key] = data
	return nil
}

func (s *memoryStore) DeleteSession(_ context.Context, key string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.deleteErr != nil {
		return s.deleteErr
	}
	delete(s.sessions, key)
	return nil
}

func (s *memoryStore) SaveOAuthState(_ context.Context, state string, data session.OAuthState, _ time.Duration) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.states[state] = data
	return nil
}

func (s *memoryStore) ConsumeOAuthState(_ context.Context, state string) (session.OAuthState, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	data, ok := s.states[state]
	delete(s.states, state)
	return data, ok, nil
}

func expiredSession() session.Data {
	now := time.Now().UTC().Add(-time.Minute)
	return session.Data{
		Username:     "demo",
		AccessToken:  "old-access",
		RefreshToken: "old-refresh",
		ExpiresAt:    now,
		CreatedAt:    now.Add(-time.Hour),
		UpdatedAt:    now,
	}
}

func mustURL(t *testing.T, raw string) *url.URL {
	t.Helper()
	parsed, err := url.Parse(raw)
	if err != nil {
		t.Fatal(err)
	}
	return parsed
}

func mustParseLocation(t *testing.T, response *http.Response) *url.URL {
	t.Helper()
	location, err := url.Parse(response.Header.Get("Location"))
	if err != nil {
		t.Fatal(err)
	}
	return location
}

func decodeJSON(t *testing.T, response *http.Response, target any) {
	t.Helper()
	defer response.Body.Close()
	if err := json.NewDecoder(response.Body).Decode(target); err != nil {
		t.Fatal(err)
	}
}

func writeJSON(t *testing.T, w http.ResponseWriter, value any) {
	t.Helper()
	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(value); err != nil {
		t.Error(err)
	}
}

func base64JSON(t *testing.T, value any) string {
	t.Helper()
	raw, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	return base64.RawURLEncoding.EncodeToString(raw)
}

func findSetCookie(response *http.Response, name string) *http.Cookie {
	for _, cookie := range response.Cookies() {
		if cookie.Name == name {
			return cookie
		}
	}
	return nil
}

func assertDocumentHeaders(t *testing.T, response *http.Response, expectHSTS bool) {
	t.Helper()
	assertHeader(t, response, "X-Content-Type-Options", "nosniff")
	assertHeader(t, response, "Referrer-Policy", "same-origin")
	assertHeader(t, response, "Content-Security-Policy", contentSecurityPolicy)
	assertHeader(t, response, "X-Frame-Options", "DENY")
	assertHeader(t, response, "Cross-Origin-Opener-Policy", "same-origin")
	assertHeader(t, response, "Cross-Origin-Resource-Policy", "same-origin")
	assertHSTS(t, response, expectHSTS)
}

func assertAPIHeaders(t *testing.T, response *http.Response, expectHSTS bool) {
	t.Helper()
	assertHeader(t, response, "X-Content-Type-Options", "nosniff")
	assertHeaderAbsent(t, response, "Referrer-Policy")
	assertHeaderAbsent(t, response, "Content-Security-Policy")
	assertHeaderAbsent(t, response, "X-Frame-Options")
	assertHeaderAbsent(t, response, "Cross-Origin-Opener-Policy")
	assertHeaderAbsent(t, response, "Cross-Origin-Resource-Policy")
	assertHSTS(t, response, expectHSTS)
}

func assertHSTS(t *testing.T, response *http.Response, expected bool) {
	t.Helper()
	if expected {
		assertHeader(t, response, "Strict-Transport-Security", strictTransportSecurity)
	} else {
		assertHeaderAbsent(t, response, "Strict-Transport-Security")
	}
}

func assertHeader(t *testing.T, response *http.Response, name, expected string) {
	t.Helper()
	if got := response.Header.Get(name); got != expected {
		t.Fatalf("%s = %q", name, got)
	}
}

func assertHeaderAbsent(t *testing.T, response *http.Response, name string) {
	t.Helper()
	if got := response.Header.Get(name); got != "" {
		t.Fatalf("%s should be absent, got %q", name, got)
	}
}

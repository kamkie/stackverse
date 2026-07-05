package gateway

import (
	"context"
	"crypto/subtle"
	"embed"
	"encoding/json"
	"errors"
	"io/fs"
	"log/slog"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"path"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"

	"github.com/kamkie/stackverse/gateways/go/internal/config"
	"github.com/kamkie/stackverse/gateways/go/internal/logx"
	"github.com/kamkie/stackverse/gateways/go/internal/session"
)

const (
	sessionCookieName    = "stackverse_session"
	loginStateCookieName = "stackverse_login_state"
	refreshSkew          = 30 * time.Second
)

//go:embed static/index.html
var bundledStatic embed.FS

type accessTokenContextKey struct{}

type Server struct {
	cfg            config.Config
	store          session.Store
	logger         *slog.Logger
	oidc           *oidcClient
	expectedOrigin string
	backendProxy   http.Handler
	frontendProxy  http.Handler
	spa            http.Handler
}

func NewHandler(cfg config.Config, store session.Store, logger *slog.Logger, httpClient *http.Client, transport http.RoundTripper) (http.Handler, error) {
	oidc := newOIDCClient(context.Background(), cfg, httpClient, logger)
	server := &Server{
		cfg:            cfg,
		store:          store,
		logger:         logger,
		oidc:           oidc,
		expectedOrigin: canonicalOrigin(cfg.PublicURL),
	}
	server.backendProxy = server.newProxy(cfg.BackendURL, "backend", true, transport)
	if cfg.FrontendURL != nil {
		server.frontendProxy = server.newProxy(cfg.FrontendURL, "frontend", false, transport)
	} else {
		spa, err := newSPAHandler(cfg.SPARoot)
		if err != nil {
			return nil, err
		}
		server.spa = spa
	}

	router := chi.NewRouter()
	router.Use(func(next http.Handler) http.Handler { return withSecurityHeaders(cfg.CookiesSecure(), next) })
	router.Use(func(next http.Handler) http.Handler { return withCSRFCookie(cfg.CookiesSecure(), next) })

	router.Get("/auth/login", server.login)
	router.Get("/auth/callback", server.callback)
	router.Post("/auth/logout", server.logout)
	router.Get("/auth/session", server.authSession)
	router.HandleFunc("/api", server.api)
	router.HandleFunc("/api/*", server.api)
	router.NotFound(server.frontend)
	return router, nil
}

func (s *Server) login(w http.ResponseWriter, r *http.Request) {
	state, err := session.NewOpaqueID()
	if err != nil {
		writeProblem(w, http.StatusInternalServerError, "Internal Server Error", "Could not start login.")
		return
	}
	verifier, err := newCodeVerifier()
	if err != nil {
		writeProblem(w, http.StatusInternalServerError, "Internal Server Error", "Could not start login.")
		return
	}
	if err := s.store.SaveOAuthState(r.Context(), state, session.OAuthState{
		CodeVerifier: verifier,
		CreatedAt:    time.Now().UTC(),
	}, session.StateTTL); err != nil {
		s.logDependencyFailure(r.Context(), "redis", "redis_write_failed", err)
		writeProblem(w, http.StatusServiceUnavailable, "Service Unavailable", "Session storage is temporarily unavailable.")
		return
	}
	http.SetCookie(w, s.loginStateCookie(state, int(session.StateTTL.Seconds())))
	http.Redirect(w, r, s.oidc.authCodeURL(state, verifier), http.StatusFound)
}

func (s *Server) callback(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	if r.URL.Query().Get("error") != "" || r.URL.Query().Get("code") == "" || r.URL.Query().Get("state") == "" {
		http.SetCookie(w, s.clearLoginStateCookie())
		logx.Event(ctx, s.logger, slog.LevelInfo, "oidc_callback_completed", "failure",
			"Authorization code flow failed",
			slog.String("error_code", "remote_failure"))
		http.Redirect(w, r, "/", http.StatusFound)
		return
	}

	queryState := r.URL.Query().Get("state")
	if !s.loginStateMatches(r, queryState) {
		http.SetCookie(w, s.clearLoginStateCookie())
		logx.Event(ctx, s.logger, slog.LevelInfo, "oidc_callback_completed", "failure",
			"Authorization code flow failed",
			slog.String("error_code", "invalid_state"))
		http.Redirect(w, r, "/", http.StatusFound)
		return
	}

	state, ok, err := s.store.ConsumeOAuthState(ctx, queryState)
	http.SetCookie(w, s.clearLoginStateCookie())
	if err != nil {
		s.logDependencyFailure(ctx, "redis", "redis_read_failed", err)
		logx.Event(ctx, s.logger, slog.LevelInfo, "oidc_callback_completed", "failure",
			"Authorization code flow failed",
			slog.String("error_code", "state_store_unavailable"))
		http.Redirect(w, r, "/", http.StatusFound)
		return
	}
	if !ok {
		logx.Event(ctx, s.logger, slog.LevelInfo, "oidc_callback_completed", "failure",
			"Authorization code flow failed",
			slog.String("error_code", "invalid_state"))
		http.Redirect(w, r, "/", http.StatusFound)
		return
	}

	data, err := s.oidc.exchange(ctx, r.URL.Query().Get("code"), state.CodeVerifier)
	if err != nil {
		logx.Event(ctx, s.logger, slog.LevelInfo, "oidc_callback_completed", "failure",
			"Authorization code flow failed",
			slog.String("error_code", "token_exchange_failed"))
		http.Redirect(w, r, "/", http.StatusFound)
		return
	}

	key, err := session.NewOpaqueID()
	if err != nil {
		writeProblem(w, http.StatusInternalServerError, "Internal Server Error", "Could not create session.")
		return
	}
	if err := s.store.SaveSession(ctx, key, data, session.SessionTTL); err != nil {
		s.logDependencyFailure(ctx, "redis", "redis_write_failed", err)
		http.Redirect(w, r, "/", http.StatusFound)
		return
	}
	http.SetCookie(w, s.sessionCookie(key, int(session.SessionTTL.Seconds())))
	logx.Event(ctx, s.logger, slog.LevelInfo, "oidc_callback_completed", "success",
		"Authorization code flow completed",
		slog.String("actor", data.Username))
	logx.Event(ctx, s.logger, slog.LevelInfo, "session_created", "success",
		"Session ticket stored in Redis, cookie issued",
		slog.String("actor", data.Username))
	http.Redirect(w, r, "/", http.StatusFound)
}

func (s *Server) logout(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	cookie, err := r.Cookie(sessionCookieName)
	if err == nil && cookie.Value != "" {
		data, ok, err := s.store.LoadSession(ctx, cookie.Value)
		if err != nil {
			s.logDependencyFailure(ctx, "redis", "redis_read_failed", err)
		}
		if err := s.store.DeleteSession(ctx, cookie.Value); err != nil {
			s.logDependencyFailure(ctx, "redis", "redis_delete_failed", err)
		}
		http.SetCookie(w, s.clearSessionCookie())
		if ok {
			logx.Event(ctx, s.logger, slog.LevelInfo, "session_destroyed", "success",
				"Session destroyed by user logout",
				slog.String("reason", "logout"),
				slog.String("actor", data.Username))
			s.oidc.logout(context.Background(), data.RefreshToken)
		}
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) authSession(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	data, ok, err := s.currentSession(r)
	if err != nil {
		s.logDependencyFailure(r.Context(), "redis", "redis_read_failed", err)
		_, _ = w.Write([]byte(`{"authenticated":false}`))
		return
	}
	if !ok {
		_, _ = w.Write([]byte(`{"authenticated":false}`))
		return
	}
	payload, _ := json.Marshal(map[string]any{"authenticated": true, "username": data.Username})
	_, _ = w.Write(payload)
}

func (s *Server) api(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	if !sameOriginStateChangeAllowed(r, s.expectedOrigin) {
		logx.Event(ctx, s.logger, slog.LevelInfo, "csrf_validation_failed", "denied",
			"Rejected a cross-origin state-changing /api request",
			slog.String("method", logx.Sanitize(r.Method, 32)),
			slog.String("path", logx.Sanitize(r.URL.Path, 200)))
		s.writeAPIProblem(w, http.StatusForbidden, "Forbidden", "Cross-origin state-changing requests are not supported.")
		return
	}
	if !validCSRF(r) {
		logx.Event(ctx, s.logger, slog.LevelInfo, "csrf_validation_failed", "denied",
			"Rejected a state-changing /api request without a matching CSRF header",
			slog.String("method", logx.Sanitize(r.Method, 32)),
			slog.String("path", logx.Sanitize(r.URL.Path, 200)))
		s.writeAPIProblem(w, http.StatusForbidden, "Forbidden", "Missing or mismatched X-XSRF-TOKEN header.")
		return
	}

	token := ""
	if cookie, err := r.Cookie(sessionCookieName); err == nil && cookie.Value != "" {
		data, ok, err := s.store.LoadSession(ctx, cookie.Value)
		if err != nil {
			s.logDependencyFailure(ctx, "redis", "redis_read_failed", err)
			s.writeAPIProblem(w, http.StatusServiceUnavailable, "Service Unavailable", "Session storage is temporarily unavailable.")
			return
		}
		if ok {
			accessToken, refreshed, err := s.ensureAccessToken(ctx, cookie.Value, data)
			if errors.Is(err, errIDPUnavailable) {
				s.writeAPIProblem(w, http.StatusServiceUnavailable, "Service Unavailable", "Authentication is temporarily unavailable; please retry.")
				return
			}
			if errors.Is(err, errRefreshRejected) {
				if err := s.store.DeleteSession(ctx, cookie.Value); err != nil {
					s.logDependencyFailure(ctx, "redis", "redis_delete_failed", err)
				}
				http.SetCookie(w, s.clearSessionCookie())
				logx.Event(ctx, s.logger, slog.LevelInfo, "session_destroyed", "success",
					"Session destroyed after a failed token refresh; request degraded to anonymous",
					slog.String("reason", "token_refresh_failed"),
					slog.String("actor", data.Username))
			} else if err != nil {
				s.logDependencyFailure(ctx, "redis", "redis_write_failed", err)
				s.writeAPIProblem(w, http.StatusServiceUnavailable, "Service Unavailable", "Session storage is temporarily unavailable.")
				return
			} else {
				token = accessToken
				if refreshed {
					http.SetCookie(w, s.sessionCookie(cookie.Value, int(session.SessionTTL.Seconds())))
				}
			}
		}
	}
	s.backendProxy.ServeHTTP(w, r.WithContext(context.WithValue(ctx, accessTokenContextKey{}, token)))
}

func (s *Server) ensureAccessToken(ctx context.Context, key string, data session.Data) (string, bool, error) {
	if data.AccessToken == "" {
		return "", false, errRefreshRejected
	}
	if time.Until(data.ExpiresAt) > refreshSkew {
		return data.AccessToken, false, nil
	}
	refreshed, err := s.oidc.refresh(ctx, data)
	if err != nil {
		return "", false, err
	}
	if err := s.store.SaveSession(ctx, key, refreshed, session.SessionTTL); err != nil {
		return "", true, err
	}
	return refreshed.AccessToken, true, nil
}

func (s *Server) frontend(w http.ResponseWriter, r *http.Request) {
	if s.frontendProxy != nil {
		s.frontendProxy.ServeHTTP(w, r)
		return
	}
	s.spa.ServeHTTP(w, r)
}

func (s *Server) currentSession(r *http.Request) (session.Data, bool, error) {
	cookie, err := r.Cookie(sessionCookieName)
	if err != nil || cookie.Value == "" {
		return session.Data{}, false, nil
	}
	return s.store.LoadSession(r.Context(), cookie.Value)
}

func (s *Server) newProxy(target *url.URL, dependency string, api bool, transport http.RoundTripper) http.Handler {
	proxy := httputil.NewSingleHostReverseProxy(target)
	originalDirector := proxy.Director
	proxy.Director = func(req *http.Request) {
		originalDirector(req)
		req.Host = target.Host
		req.Header.Del("Cookie")
		if api {
			req.Header.Del("Authorization")
			req.Header.Del(csrfHeaderName)
			if token, _ := req.Context().Value(accessTokenContextKey{}).(string); token != "" {
				req.Header.Set("Authorization", "Bearer "+token)
			}
		}
	}
	proxy.Transport = transport
	proxy.ModifyResponse = func(response *http.Response) error {
		if api {
			applyAPISecurityHeaders(response.Header, s.cfg.CookiesSecure())
		}
		return nil
	}
	proxy.ErrorHandler = func(w http.ResponseWriter, r *http.Request, err error) {
		s.logDependencyFailure(r.Context(), dependency, dependency+"_unavailable", err)
		if api {
			s.writeAPIProblem(w, http.StatusBadGateway, "Bad Gateway", "The upstream API is temporarily unavailable.")
			return
		}
		http.Error(w, "Bad Gateway", http.StatusBadGateway)
	}
	return proxy
}

func (s *Server) sessionCookie(value string, maxAge int) *http.Cookie {
	return &http.Cookie{
		Name:     sessionCookieName,
		Value:    value,
		Path:     "/",
		MaxAge:   maxAge,
		Secure:   s.cfg.CookiesSecure(),
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
	}
}

func (s *Server) loginStateCookie(value string, maxAge int) *http.Cookie {
	return &http.Cookie{
		Name:     loginStateCookieName,
		Value:    value,
		Path:     "/auth/callback",
		MaxAge:   maxAge,
		Secure:   s.cfg.CookiesSecure(),
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
	}
}

func (s *Server) clearLoginStateCookie() *http.Cookie {
	cookie := s.loginStateCookie("", -1)
	cookie.Expires = time.Unix(0, 0)
	return cookie
}

func (s *Server) clearSessionCookie() *http.Cookie {
	cookie := s.sessionCookie("", -1)
	cookie.Expires = time.Unix(0, 0)
	return cookie
}

func (s *Server) loginStateMatches(r *http.Request, queryState string) bool {
	cookie, err := r.Cookie(loginStateCookieName)
	if err != nil || cookie.Value == "" || queryState == "" || len(cookie.Value) != len(queryState) {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(cookie.Value), []byte(queryState)) == 1
}

func (s *Server) writeAPIProblem(w http.ResponseWriter, status int, title, detail string) {
	applyAPISecurityHeaders(w.Header(), s.cfg.CookiesSecure())
	writeProblem(w, status, title, detail)
}

func (s *Server) logDependencyFailure(ctx context.Context, dependency, code string, err error) {
	logx.Event(ctx, s.logger, slog.LevelError, "dependency_call_failed", "failure",
		dependency+" call failed",
		slog.String("dependency", dependency),
		slog.String("error_code", code),
		slog.String("error", err.Error()))
}

func newSPAHandler(spaRoot string) (http.Handler, error) {
	var root fs.FS
	if spaRoot != "" {
		root = os.DirFS(spaRoot)
	} else {
		sub, err := fs.Sub(bundledStatic, "static")
		if err != nil {
			return nil, err
		}
		root = sub
	}
	return spaHandler{root: root, files: http.FileServer(http.FS(root))}, nil
}

type spaHandler struct {
	root  fs.FS
	files http.Handler
}

func (h spaHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		http.NotFound(w, r)
		return
	}
	name := strings.TrimPrefix(path.Clean("/"+r.URL.Path), "/")
	if name == "" {
		name = "index.html"
	}
	if !h.exists(name) {
		r = r.Clone(r.Context())
		r.URL.Path = "/index.html"
	}
	h.files.ServeHTTP(w, r)
}

func (h spaHandler) exists(name string) bool {
	file, err := h.root.Open(name)
	if err != nil {
		return false
	}
	defer file.Close()
	stat, err := file.Stat()
	return err == nil && !stat.IsDir()
}

package config

import "testing"

func TestLoadDefaultsUseSharedGatewayContractValues(t *testing.T) {
	clearConfigEnv(t)

	cfg, err := Load()
	if err != nil {
		t.Fatal(err)
	}

	if cfg.Port != "8000" {
		t.Fatalf("Port = %q", cfg.Port)
	}
	if cfg.BackendURL.String() != "http://localhost:8080" {
		t.Fatalf("BackendURL = %q", cfg.BackendURL)
	}
	if cfg.FrontendURL != nil {
		t.Fatalf("FrontendURL = %q", cfg.FrontendURL)
	}
	if cfg.FrontendURLString() != "" {
		t.Fatalf("FrontendURLString = %q", cfg.FrontendURLString())
	}
	if cfg.RedisURL != "redis://localhost:6379" {
		t.Fatalf("RedisURL = %q", cfg.RedisURL)
	}
	if cfg.OIDCIssuerURI != "http://localhost:8180/realms/stackverse" {
		t.Fatalf("OIDCIssuerURI = %q", cfg.OIDCIssuerURI)
	}
	if cfg.OIDCInternalIssuerURI != cfg.OIDCIssuerURI {
		t.Fatalf("OIDCInternalIssuerURI = %q", cfg.OIDCInternalIssuerURI)
	}
	if cfg.OIDCClientID != "stackverse-gateway" {
		t.Fatalf("OIDCClientID = %q", cfg.OIDCClientID)
	}
	if cfg.OIDCClientSecret != "stackverse-secret" {
		t.Fatalf("OIDCClientSecret = %q", cfg.OIDCClientSecret)
	}
	if cfg.PublicURL.String() != "http://localhost:8000" {
		t.Fatalf("PublicURL = %q", cfg.PublicURL)
	}
	if cfg.CookiesSecure() {
		t.Fatalf("local default should not set secure cookies")
	}
	if cfg.LogLevel != "info" || cfg.LogFormat != "json" {
		t.Fatalf("logging = %q/%q", cfg.LogLevel, cfg.LogFormat)
	}
}

func TestLoadHonorsOverridesAndTrimsIssuerURLs(t *testing.T) {
	clearConfigEnv(t)
	t.Setenv("PORT", "9000")
	t.Setenv("BACKEND_URL", "https://api.stackverse.example")
	t.Setenv("FRONTEND_URL", "http://frontend.internal:5173")
	t.Setenv("SPA_ROOT", "dist")
	t.Setenv("REDIS_URL", "redis://:secret@redis:6380/1")
	t.Setenv("OIDC_ISSUER_URI", "https://idp.example/realms/stackverse/")
	t.Setenv("OIDC_INTERNAL_ISSUER_URI", "http://keycloak:8080/realms/stackverse/")
	t.Setenv("OIDC_CLIENT_ID", "gateway-client")
	t.Setenv("OIDC_CLIENT_SECRET", "super-secret")
	t.Setenv("PUBLIC_URL", "https://stackverse.example/base")
	t.Setenv("LOG_LEVEL", "debug")
	t.Setenv("LOG_FORMAT", "text")

	cfg, err := Load()
	if err != nil {
		t.Fatal(err)
	}

	if cfg.Port != "9000" {
		t.Fatalf("Port = %q", cfg.Port)
	}
	if cfg.BackendURL.String() != "https://api.stackverse.example" {
		t.Fatalf("BackendURL = %q", cfg.BackendURL)
	}
	if cfg.FrontendURLString() != "http://frontend.internal:5173" {
		t.Fatalf("FrontendURL = %q", cfg.FrontendURLString())
	}
	if cfg.SPARoot != "dist" {
		t.Fatalf("SPARoot = %q", cfg.SPARoot)
	}
	if cfg.RedisEndpointForLogs() != "redis:6380" {
		t.Fatalf("RedisEndpointForLogs = %q", cfg.RedisEndpointForLogs())
	}
	if cfg.OIDCIssuerURI != "https://idp.example/realms/stackverse" {
		t.Fatalf("OIDCIssuerURI = %q", cfg.OIDCIssuerURI)
	}
	if cfg.OIDCInternalIssuerURI != "http://keycloak:8080/realms/stackverse" {
		t.Fatalf("OIDCInternalIssuerURI = %q", cfg.OIDCInternalIssuerURI)
	}
	if cfg.OIDCClientID != "gateway-client" || cfg.OIDCClientSecret != "super-secret" {
		t.Fatalf("OIDC client = %q/%q", cfg.OIDCClientID, cfg.OIDCClientSecret)
	}
	if !cfg.CookiesSecure() {
		t.Fatalf("https public URL should set secure cookies")
	}
	if cfg.RedirectURI() != "https://stackverse.example/auth/callback" {
		t.Fatalf("RedirectURI = %q", cfg.RedirectURI())
	}
	if cfg.LogLevel != "debug" || cfg.LogFormat != "text" {
		t.Fatalf("logging = %q/%q", cfg.LogLevel, cfg.LogFormat)
	}
}

func TestLoadRejectsInvalidAbsoluteURLs(t *testing.T) {
	tests := []struct {
		name  string
		env   string
		value string
	}{
		{name: "backend", env: "BACKEND_URL", value: "localhost:8080"},
		{name: "frontend", env: "FRONTEND_URL", value: "/dev-server"},
		{name: "public", env: "PUBLIC_URL", value: "stackverse.example"},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			clearConfigEnv(t)
			t.Setenv(tt.env, tt.value)
			if _, err := Load(); err == nil {
				t.Fatalf("expected %s validation error", tt.env)
			}
		})
	}
}

func TestRedisEndpointForLogsRedactsUserInfo(t *testing.T) {
	cfg := Config{RedisURL: "redis://:secret@redis:6379/0"}
	if got := cfg.RedisEndpointForLogs(); got != "redis:6379" {
		t.Fatalf("endpoint = %q", got)
	}
}

func TestRedisEndpointForLogsHandlesURLAndBareAddressForms(t *testing.T) {
	tests := []struct {
		name string
		raw  string
		want string
	}{
		{name: "url default port", raw: "redis://cache", want: "cache:6379"},
		{name: "url ipv6", raw: "redis://:secret@[::1]:6380/0", want: "[::1]:6380"},
		{name: "bare host", raw: "cache", want: "cache"},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			cfg := Config{RedisURL: tt.raw}
			if got := cfg.RedisEndpointForLogs(); got != tt.want {
				t.Fatalf("endpoint = %q, want %q", got, tt.want)
			}
		})
	}
}

func TestRedirectURIUsesPublicURL(t *testing.T) {
	public, err := parseURL("https://stackverse.example/base", "PUBLIC_URL")
	if err != nil {
		t.Fatal(err)
	}
	cfg := Config{PublicURL: public}
	if got := cfg.RedirectURI(); got != "https://stackverse.example/auth/callback" {
		t.Fatalf("redirect URI = %q", got)
	}
}

func clearConfigEnv(t *testing.T) {
	t.Helper()
	for _, name := range []string{
		"PORT",
		"BACKEND_URL",
		"FRONTEND_URL",
		"SPA_ROOT",
		"REDIS_URL",
		"OIDC_ISSUER_URI",
		"OIDC_INTERNAL_ISSUER_URI",
		"OIDC_CLIENT_ID",
		"OIDC_CLIENT_SECRET",
		"PUBLIC_URL",
		"LOG_LEVEL",
		"LOG_FORMAT",
	} {
		t.Setenv(name, "")
	}
}

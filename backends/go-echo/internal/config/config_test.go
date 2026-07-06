package config

import "testing"

func TestLoadDefaults(t *testing.T) {
	for _, name := range []string{
		"PORT", "DB_HOST", "DB_PORT", "DB_NAME", "DB_USER", "DB_PASSWORD",
		"OIDC_ISSUER_URI", "OIDC_JWKS_URI", "SEED_MESSAGES_DIR", "LOG_LEVEL", "LOG_FORMAT",
	} {
		t.Setenv(name, "")
	}

	cfg := Load()
	if cfg.Port != "8080" ||
		cfg.DBHost != "localhost" ||
		cfg.DBPort != "5432" ||
		cfg.DBName != "stackverse" ||
		cfg.DBUser != "stackverse" ||
		cfg.DBPassword != "stackverse" ||
		cfg.IssuerURI != "http://localhost:8180/realms/stackverse" ||
		cfg.JWKSURI != "" ||
		cfg.SeedMessagesDir != "../../spec/messages" ||
		cfg.LogLevel != "info" ||
		cfg.LogFormat != "json" {
		t.Fatalf("unexpected defaults: %+v", cfg)
	}
}

func TestLoadUsesEnvironmentAndBuildsDSN(t *testing.T) {
	t.Setenv("PORT", "9090")
	t.Setenv("DB_HOST", "postgres")
	t.Setenv("DB_PORT", "15432")
	t.Setenv("DB_NAME", "bookmarks")
	t.Setenv("DB_USER", "app")
	t.Setenv("DB_PASSWORD", "secret")
	t.Setenv("OIDC_ISSUER_URI", "https://idp.example/realms/stackverse")
	t.Setenv("OIDC_JWKS_URI", "https://idp.internal/jwks")
	t.Setenv("SEED_MESSAGES_DIR", "/contract/messages")
	t.Setenv("LOG_LEVEL", "debug")
	t.Setenv("LOG_FORMAT", "text")

	cfg := Load()
	if cfg.Port != "9090" ||
		cfg.IssuerURI != "https://idp.example/realms/stackverse" ||
		cfg.JWKSURI != "https://idp.internal/jwks" ||
		cfg.SeedMessagesDir != "/contract/messages" ||
		cfg.LogLevel != "debug" ||
		cfg.LogFormat != "text" {
		t.Fatalf("environment overrides not applied: %+v", cfg)
	}

	want := "host=postgres port=15432 dbname=bookmarks user=app password=secret sslmode=disable"
	if got := cfg.DSN(); got != want {
		t.Fatalf("DSN() = %q, want %q", got, want)
	}
}

// Package config loads the backend configuration from environment variables —
// the environment is the configuration (backends/README.md), no files, no profiles.
package config

import (
	"fmt"
	"os"
)

// Audience is the `aud` claim every access token must carry; fixed by the
// realm import (infra/keycloak), not configurable per deployment.
const Audience = "stackverse-api"

type Config struct {
	Port       string
	DBHost     string
	DBPort     string
	DBName     string
	DBUser     string
	DBPassword string
	// IssuerURI is the expected `iss` claim; also the OIDC discovery base when
	// JWKSURI is empty.
	IssuerURI string
	// JWKSURI overrides key discovery when the issuer host is not dialable
	// from the container (compose); `iss` validation still uses IssuerURI.
	JWKSURI string
	// SeedMessagesDir holds the contract's message seed (spec/messages);
	// language = filename (SPEC rule 12).
	SeedMessagesDir string
	LogLevel        string
	LogFormat       string
}

func Load() Config {
	return Config{
		Port:            env("PORT", "8080"),
		DBHost:          env("DB_HOST", "localhost"),
		DBPort:          env("DB_PORT", "5432"),
		DBName:          env("DB_NAME", "stackverse"),
		DBUser:          env("DB_USER", "stackverse"),
		DBPassword:      env("DB_PASSWORD", "stackverse"),
		IssuerURI:       env("OIDC_ISSUER_URI", "http://localhost:8180/realms/stackverse"),
		JWKSURI:         env("OIDC_JWKS_URI", ""),
		SeedMessagesDir: env("SEED_MESSAGES_DIR", "../../spec/messages"),
		LogLevel:        env("LOG_LEVEL", "info"),
		LogFormat:       env("LOG_FORMAT", "json"),
	}
}

// DSN is the PostgreSQL connection string. Scanning timestamps back in UTC is
// handled by the timestamptz codec registration in internal/store.
func (c Config) DSN() string {
	return fmt.Sprintf(
		"host=%s port=%s dbname=%s user=%s password=%s sslmode=disable",
		c.DBHost, c.DBPort, c.DBName, c.DBUser, c.DBPassword,
	)
}

func env(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}

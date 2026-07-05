// Package config loads gateway configuration from environment variables.
package config

import (
	"fmt"
	"net"
	"net/url"
	"os"
	"strings"
)

type Config struct {
	Port                  string
	BackendURL            *url.URL
	FrontendURL           *url.URL
	SPARoot               string
	RedisURL              string
	OIDCIssuerURI         string
	OIDCInternalIssuerURI string
	OIDCClientID          string
	OIDCClientSecret      string
	PublicURL             *url.URL
	LogLevel              string
	LogFormat             string
}

func Load() (Config, error) {
	backendURL, err := parseURL(env("BACKEND_URL", "http://localhost:8080"), "BACKEND_URL")
	if err != nil {
		return Config{}, err
	}
	var frontendURL *url.URL
	if raw := env("FRONTEND_URL", ""); raw != "" {
		frontendURL, err = parseURL(raw, "FRONTEND_URL")
		if err != nil {
			return Config{}, err
		}
	}
	publicURL, err := parseURL(env("PUBLIC_URL", "http://localhost:8000"), "PUBLIC_URL")
	if err != nil {
		return Config{}, err
	}

	issuer := strings.TrimRight(env("OIDC_ISSUER_URI", "http://localhost:8180/realms/stackverse"), "/")
	internalIssuer := strings.TrimRight(env("OIDC_INTERNAL_ISSUER_URI", ""), "/")
	if internalIssuer == "" {
		internalIssuer = issuer
	}

	return Config{
		Port:                  env("PORT", "8000"),
		BackendURL:            backendURL,
		FrontendURL:           frontendURL,
		SPARoot:               env("SPA_ROOT", ""),
		RedisURL:              env("REDIS_URL", "redis://localhost:6379"),
		OIDCIssuerURI:         issuer,
		OIDCInternalIssuerURI: internalIssuer,
		OIDCClientID:          env("OIDC_CLIENT_ID", "stackverse-gateway"),
		OIDCClientSecret:      env("OIDC_CLIENT_SECRET", "stackverse-secret"),
		PublicURL:             publicURL,
		LogLevel:              env("LOG_LEVEL", "info"),
		LogFormat:             env("LOG_FORMAT", "json"),
	}, nil
}

func (c Config) CookiesSecure() bool {
	return strings.EqualFold(c.PublicURL.Scheme, "https")
}

func (c Config) RedirectURI() string {
	redirect := *c.PublicURL
	redirect.Path = "/auth/callback"
	redirect.RawQuery = ""
	redirect.Fragment = ""
	return redirect.String()
}

func (c Config) FrontendURLString() string {
	if c.FrontendURL == nil {
		return ""
	}
	return c.FrontendURL.String()
}

func (c Config) RedisEndpointForLogs() string {
	parsed, err := url.Parse(c.RedisURL)
	if err == nil && parsed.Scheme != "" {
		host := parsed.Hostname()
		port := parsed.Port()
		if port == "" {
			port = "6379"
		}
		return net.JoinHostPort(host, port)
	}
	if strings.Contains(c.RedisURL, "@") {
		return ""
	}
	return c.RedisURL
}

func parseURL(raw, name string) (*url.URL, error) {
	parsed, err := url.Parse(raw)
	if err != nil || parsed.Scheme == "" || parsed.Host == "" {
		return nil, fmt.Errorf("%s must be an absolute URL", name)
	}
	return parsed, nil
}

func env(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}

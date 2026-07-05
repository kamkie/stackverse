package config

import "testing"

func TestRedisEndpointForLogsRedactsUserInfo(t *testing.T) {
	cfg := Config{RedisURL: "redis://:secret@redis:6379/0"}
	if got := cfg.RedisEndpointForLogs(); got != "redis:6379" {
		t.Fatalf("endpoint = %q", got)
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

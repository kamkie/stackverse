// Package auth validates bearer JWTs against the IdP's JWKS and carries the
// caller's identity through the request context. Identity is the
// `preferred_username` claim, roles come from `realm_access.roles`, and the
// admin ⊃ moderator hierarchy stays in Keycloak (backends/README.md).
package auth

import (
	"context"
	"crypto/rsa"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"math/big"
	"net/http"
	"sync"
	"time"

	"github.com/kamkie/stackverse/backends/go/internal/logx"
)

// refreshCooldown rate-limits JWKS refetches triggered by unknown key ids, so
// a flood of forged-kid tokens cannot hammer the IdP.
const refreshCooldown = 30 * time.Second

// Keys is a lazy, self-refreshing JWKS cache. The key-set URI comes from
// configuration (compose) or OIDC discovery on the issuer — resolved on first
// use, not at startup, so the service comes up while the IdP is still booting.
type Keys struct {
	issuer  string
	jwksURI string
	client  *http.Client
	logger  *slog.Logger

	mu          sync.Mutex
	resolvedURI string
	keys        map[string]*rsa.PublicKey
	lastRefresh time.Time
}

func NewKeys(issuer, jwksURI string, logger *slog.Logger) *Keys {
	return &Keys{
		issuer:  issuer,
		jwksURI: jwksURI,
		client:  &http.Client{Timeout: 10 * time.Second},
		logger:  logger,
	}
}

// Key returns the RSA public key for a token's kid header, refreshing the
// cached JWKS (rate-limited) when the kid is unknown — how key rotation is
// picked up without restarts.
func (k *Keys) Key(ctx context.Context, kid string) (*rsa.PublicKey, error) {
	k.mu.Lock()
	defer k.mu.Unlock()
	if key, ok := k.keys[kid]; ok {
		return key, nil
	}
	if time.Since(k.lastRefresh) < refreshCooldown {
		return nil, fmt.Errorf("unknown signing key %q", kid)
	}
	if err := k.refreshLocked(ctx); err != nil {
		return nil, err
	}
	if key, ok := k.keys[kid]; ok {
		return key, nil
	}
	return nil, fmt.Errorf("unknown signing key %q", kid)
}

func (k *Keys) refreshLocked(ctx context.Context) error {
	k.lastRefresh = time.Now()
	uri := k.resolvedURI
	if uri == "" {
		discovered, err := k.discoverJWKSURI(ctx)
		if err != nil {
			return err
		}
		k.resolvedURI = discovered
		uri = discovered
	}
	started := time.Now()
	var document struct {
		Keys []struct {
			Kty string `json:"kty"`
			Kid string `json:"kid"`
			Use string `json:"use"`
			N   string `json:"n"`
			E   string `json:"e"`
		} `json:"keys"`
	}
	if err := k.getJSON(ctx, uri, &document); err != nil {
		logx.Event(ctx, k.logger, slog.LevelError, "dependency_call_failed", "failure",
			"Fetching the JWKS from the IdP failed",
			slog.String("dependency", "keycloak"),
			slog.Int64("duration_ms", time.Since(started).Milliseconds()),
			slog.String("error_code", "jwks_fetch_failed"),
			slog.String("error", err.Error()),
		)
		return err
	}
	keys := make(map[string]*rsa.PublicKey)
	for _, jwk := range document.Keys {
		if jwk.Kty != "RSA" || (jwk.Use != "" && jwk.Use != "sig") {
			continue
		}
		key, err := rsaKey(jwk.N, jwk.E)
		if err != nil {
			continue // one malformed entry must not poison the rest of the set
		}
		keys[jwk.Kid] = key
	}
	if len(keys) == 0 {
		return errors.New("the JWKS document contains no usable RSA signing keys")
	}
	k.keys = keys
	return nil
}

// discoverJWKSURI resolves jwks_uri from the issuer's OIDC discovery document,
// used when no explicit OIDC_JWKS_URI is configured.
func (k *Keys) discoverJWKSURI(ctx context.Context) (string, error) {
	if k.jwksURI != "" {
		return k.jwksURI, nil
	}
	started := time.Now()
	var document struct {
		JWKSURI string `json:"jwks_uri"`
	}
	err := k.getJSON(ctx, k.issuer+"/.well-known/openid-configuration", &document)
	if err == nil && document.JWKSURI == "" {
		err = errors.New("the discovery document carries no jwks_uri")
	}
	if err != nil {
		logx.Event(ctx, k.logger, slog.LevelError, "dependency_call_failed", "failure",
			"OIDC discovery against the issuer failed",
			slog.String("dependency", "keycloak"),
			slog.Int64("duration_ms", time.Since(started).Milliseconds()),
			slog.String("error_code", "oidc_discovery_failed"),
			slog.String("error", err.Error()),
		)
		return "", err
	}
	return document.JWKSURI, nil
}

func (k *Keys) getJSON(ctx context.Context, uri string, target any) error {
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, uri, nil)
	if err != nil {
		return err
	}
	response, err := k.client.Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return fmt.Errorf("GET %s answered %d", uri, response.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, 1<<20))
	if err != nil {
		return err
	}
	return json.Unmarshal(body, target)
}

func rsaKey(n, e string) (*rsa.PublicKey, error) {
	modulus, err := base64.RawURLEncoding.DecodeString(n)
	if err != nil {
		return nil, err
	}
	exponent, err := base64.RawURLEncoding.DecodeString(e)
	if err != nil {
		return nil, err
	}
	return &rsa.PublicKey{
		N: new(big.Int).SetBytes(modulus),
		E: int(new(big.Int).SetBytes(exponent).Int64()),
	}, nil
}

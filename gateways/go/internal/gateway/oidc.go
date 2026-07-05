package gateway

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/coreos/go-oidc/v3/oidc"
	"golang.org/x/oauth2"

	"github.com/kamkie/stackverse/gateways/go/internal/config"
	"github.com/kamkie/stackverse/gateways/go/internal/logx"
	"github.com/kamkie/stackverse/gateways/go/internal/session"
)

var (
	errRefreshRejected = errors.New("refresh rejected by idp")
	errIDPUnavailable  = errors.New("idp unavailable")
)

type oidcClient struct {
	config     config.Config
	oauth      oauth2.Config
	logoutURL  string
	verifier   *oidc.IDTokenVerifier
	httpClient *http.Client
	logger     *slog.Logger
}

type idTokenClaims struct {
	Subject           string `json:"sub"`
	PreferredUsername string `json:"preferred_username"`
}

func newOIDCClient(ctx context.Context, cfg config.Config, httpClient *http.Client, logger *slog.Logger) *oidcClient {
	authURL := cfg.OIDCIssuerURI + "/protocol/openid-connect/auth"
	internal := cfg.OIDCInternalIssuerURI
	tokenURL := internal + "/protocol/openid-connect/token"
	jwksURL := internal + "/protocol/openid-connect/certs"
	logoutURL := internal + "/protocol/openid-connect/logout"

	clientCtx := oidc.ClientContext(ctx, httpClient)
	keySet := oidc.NewRemoteKeySet(clientCtx, jwksURL)
	verifier := oidc.NewVerifier(cfg.OIDCIssuerURI, keySet, &oidc.Config{ClientID: cfg.OIDCClientID})

	return &oidcClient{
		config: cfg,
		oauth: oauth2.Config{
			ClientID:     cfg.OIDCClientID,
			ClientSecret: cfg.OIDCClientSecret,
			RedirectURL:  cfg.RedirectURI(),
			Scopes:       []string{oidc.ScopeOpenID, "profile", "email"},
			Endpoint: oauth2.Endpoint{
				AuthURL:  authURL,
				TokenURL: tokenURL,
			},
		},
		logoutURL:  logoutURL,
		verifier:   verifier,
		httpClient: httpClient,
		logger:     logger,
	}
}

func (c *oidcClient) authCodeURL(state, verifier string) string {
	return c.oauth.AuthCodeURL(state,
		oauth2.AccessTypeOffline,
		oauth2.SetAuthURLParam("code_challenge", codeChallenge(verifier)),
		oauth2.SetAuthURLParam("code_challenge_method", "S256"),
	)
}

func (c *oidcClient) exchange(ctx context.Context, code, verifier string) (session.Data, error) {
	ctx = context.WithValue(ctx, oauth2.HTTPClient, c.httpClient)
	token, err := c.oauth.Exchange(ctx, code, oauth2.SetAuthURLParam("code_verifier", verifier))
	if err != nil {
		return session.Data{}, err
	}
	rawIDToken, _ := token.Extra("id_token").(string)
	if rawIDToken == "" {
		return session.Data{}, errors.New("id_token missing")
	}
	idToken, err := c.verifier.Verify(ctx, rawIDToken)
	if err != nil {
		return session.Data{}, err
	}
	var claims idTokenClaims
	if err := idToken.Claims(&claims); err != nil {
		return session.Data{}, err
	}
	username := claims.PreferredUsername
	if username == "" {
		username = claims.Subject
	}
	if username == "" || token.AccessToken == "" || token.RefreshToken == "" {
		return session.Data{}, errors.New("token response missing required fields")
	}
	now := time.Now().UTC()
	return session.Data{
		Username:     username,
		AccessToken:  token.AccessToken,
		RefreshToken: token.RefreshToken,
		IDToken:      rawIDToken,
		ExpiresAt:    token.Expiry.UTC(),
		CreatedAt:    now,
		UpdatedAt:    now,
	}, nil
}

func (c *oidcClient) refresh(ctx context.Context, data session.Data) (session.Data, error) {
	form := url.Values{
		"grant_type":    {"refresh_token"},
		"refresh_token": {data.RefreshToken},
		"client_id":     {c.config.OIDCClientID},
		"client_secret": {c.config.OIDCClientSecret},
	}
	start := time.Now()
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, c.oauth.Endpoint.TokenURL, strings.NewReader(form.Encode()))
	if err != nil {
		return session.Data{}, err
	}
	request.Header.Set("Content-Type", "application/x-www-form-urlencoded")

	response, err := c.httpClient.Do(request)
	if err != nil {
		logx.Event(ctx, c.logger, slog.LevelError, "dependency_call_failed", "failure",
			"Keycloak was unreachable during token refresh; the session is kept",
			slog.String("dependency", "keycloak"),
			slog.Int64("duration_ms", time.Since(start).Milliseconds()),
			slog.String("error_code", "idp_unreachable"))
		return session.Data{}, errIDPUnavailable
	}
	defer response.Body.Close()

	if response.StatusCode < 200 || response.StatusCode >= 300 {
		if response.StatusCode == http.StatusBadRequest || response.StatusCode == http.StatusUnauthorized {
			logx.Event(ctx, c.logger, slog.LevelWarn, "token_refresh_failed", "failure",
				"Token refresh rejected by the IdP; treating the session as expired",
				slog.String("error_code", "idp_rejected"),
				slog.Int("idp_status", response.StatusCode))
			return session.Data{}, errRefreshRejected
		}
		logx.Event(ctx, c.logger, slog.LevelError, "dependency_call_failed", "failure",
			"Keycloak failed during token refresh; the session is kept",
			slog.String("dependency", "keycloak"),
			slog.Int64("duration_ms", time.Since(start).Milliseconds()),
			slog.String("error_code", "idp_status_"+strconv.Itoa(response.StatusCode)))
		return session.Data{}, errIDPUnavailable
	}

	var payload struct {
		AccessToken  string `json:"access_token"`
		RefreshToken string `json:"refresh_token"`
		ExpiresIn    int    `json:"expires_in"`
		IDToken      string `json:"id_token"`
	}
	if err := json.NewDecoder(response.Body).Decode(&payload); err != nil {
		logx.Event(ctx, c.logger, slog.LevelError, "dependency_call_failed", "failure",
			"Keycloak returned an unreadable token response; the session is kept",
			slog.String("dependency", "keycloak"),
			slog.Int64("duration_ms", time.Since(start).Milliseconds()),
			slog.String("error_code", "idp_bad_response"))
		return session.Data{}, errIDPUnavailable
	}
	if payload.AccessToken == "" {
		logx.Event(ctx, c.logger, slog.LevelError, "dependency_call_failed", "failure",
			"Keycloak returned a token response without an access token; the session is kept",
			slog.String("dependency", "keycloak"),
			slog.Int64("duration_ms", time.Since(start).Milliseconds()),
			slog.String("error_code", "idp_bad_response"))
		return session.Data{}, errIDPUnavailable
	}
	if payload.RefreshToken == "" {
		payload.RefreshToken = data.RefreshToken
	}
	if payload.ExpiresIn <= 0 {
		payload.ExpiresIn = 300
	}
	if payload.IDToken == "" {
		payload.IDToken = data.IDToken
	}
	data.AccessToken = payload.AccessToken
	data.RefreshToken = payload.RefreshToken
	data.IDToken = payload.IDToken
	data.ExpiresAt = time.Now().UTC().Add(time.Duration(payload.ExpiresIn) * time.Second)
	data.UpdatedAt = time.Now().UTC()
	return data, nil
}

func (c *oidcClient) logout(ctx context.Context, refreshToken string) {
	if refreshToken == "" {
		return
	}
	form := url.Values{
		"client_id":     {c.config.OIDCClientID},
		"client_secret": {c.config.OIDCClientSecret},
		"refresh_token": {refreshToken},
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, c.logoutURL, strings.NewReader(form.Encode()))
	if err != nil {
		return
	}
	request.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	response, err := c.httpClient.Do(request)
	if err != nil {
		logx.Event(ctx, c.logger, slog.LevelWarn, "idp_logout_failed", "failure",
			"IdP logout failed; local session destroyed anyway",
			slog.String("error_code", "idp_unreachable"))
		return
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		logx.Event(ctx, c.logger, slog.LevelWarn, "idp_logout_failed", "failure",
			fmt.Sprintf("IdP logout returned %d; local session destroyed anyway", response.StatusCode),
			slog.String("error_code", "idp_rejected"),
			slog.Int("idp_status", response.StatusCode))
	}
}

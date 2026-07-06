use std::collections::HashMap;
use std::sync::Arc;
use std::time::{Duration, Instant};

use anyhow::{Context, anyhow};
use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use chrono::{TimeDelta, Utc};
use jsonwebtoken::{Algorithm, DecodingKey, Validation, decode, decode_header};
use serde::Deserialize;
use sha2::{Digest, Sha256};
use thiserror::Error;
use tokio::sync::Mutex;
use url::Url;

use crate::config::Config;
use crate::session::SessionData;

const JWKS_REFRESH_COOLDOWN: Duration = Duration::from_secs(30);

#[derive(Clone)]
pub struct OidcClient {
    config: Arc<Config>,
    http: reqwest::Client,
    authorization_url: String,
    token_url: String,
    logout_url: String,
    jwks_url: String,
    jwks: Arc<Mutex<JwksState>>,
}

#[derive(Default)]
struct JwksState {
    keys: HashMap<String, DecodingKey>,
    last_refresh: Option<Instant>,
}

#[derive(Deserialize)]
struct JwksDocument {
    keys: Vec<Jwk>,
}

#[derive(Deserialize)]
struct Jwk {
    kid: String,
    kty: String,
    n: String,
    e: String,
}

#[derive(Deserialize)]
struct TokenResponse {
    access_token: String,
    #[serde(default)]
    refresh_token: Option<String>,
    #[serde(default)]
    id_token: Option<String>,
    #[serde(default)]
    expires_in: Option<i64>,
}

#[derive(Debug, Deserialize)]
struct IdClaims {
    sub: String,
    #[serde(default)]
    preferred_username: Option<String>,
}

#[derive(Debug, Error)]
pub enum RefreshError {
    #[error("refresh rejected by idp")]
    Rejected,
    #[error("idp unavailable")]
    Unavailable,
}

impl OidcClient {
    pub fn new(config: Arc<Config>, http: reqwest::Client) -> Self {
        let issuer = config.oidc_issuer_uri.clone();
        let internal = config.oidc_internal_issuer_uri.clone();
        Self {
            config,
            http,
            authorization_url: format!("{issuer}/protocol/openid-connect/auth"),
            token_url: format!("{internal}/protocol/openid-connect/token"),
            logout_url: format!("{internal}/protocol/openid-connect/logout"),
            jwks_url: format!("{internal}/protocol/openid-connect/certs"),
            jwks: Arc::new(Mutex::new(JwksState::default())),
        }
    }

    pub fn authorization_redirect(&self, state: &str, verifier: &str) -> anyhow::Result<String> {
        let mut url = Url::parse(&self.authorization_url)?;
        url.query_pairs_mut()
            .append_pair("response_type", "code")
            .append_pair("client_id", &self.config.oidc_client_id)
            .append_pair("redirect_uri", &self.config.redirect_uri())
            .append_pair("scope", "openid profile email")
            .append_pair("state", state)
            .append_pair("code_challenge", &code_challenge(verifier))
            .append_pair("code_challenge_method", "S256");
        Ok(url.to_string())
    }

    pub async fn exchange(&self, code: &str, verifier: &str) -> anyhow::Result<SessionData> {
        let response = self
            .http
            .post(&self.token_url)
            .form(&[
                ("grant_type", "authorization_code"),
                ("code", code),
                ("redirect_uri", &self.config.redirect_uri()),
                ("client_id", &self.config.oidc_client_id),
                ("client_secret", &self.config.oidc_client_secret),
                ("code_verifier", verifier),
            ])
            .send()
            .await
            .context("exchange authorization code")?;
        if !response.status().is_success() {
            return Err(anyhow!("token endpoint returned {}", response.status()));
        }
        let payload = response.json::<TokenResponse>().await?;
        let raw_id_token = payload
            .id_token
            .context("token response missing id_token")?;
        let username = self.validate_id_token(&raw_id_token).await?;
        if payload.access_token.is_empty() {
            return Err(anyhow!("token response missing access token"));
        }
        let refresh_token = payload
            .refresh_token
            .filter(|value| !value.is_empty())
            .context("token response missing refresh token")?;
        let now = Utc::now();
        Ok(SessionData {
            username,
            access_token: payload.access_token,
            refresh_token,
            id_token: raw_id_token,
            expires_at: now + TimeDelta::seconds(payload.expires_in.unwrap_or(300).max(1)),
            created_at: now,
            updated_at: now,
        })
    }

    pub async fn refresh(&self, mut data: SessionData) -> Result<SessionData, RefreshError> {
        let started = Instant::now();
        let response = self
            .http
            .post(&self.token_url)
            .form(&[
                ("grant_type", "refresh_token"),
                ("refresh_token", data.refresh_token.as_str()),
                ("client_id", &self.config.oidc_client_id),
                ("client_secret", &self.config.oidc_client_secret),
            ])
            .send()
            .await
            .map_err(|err| {
                tracing::error!(
                    event = "dependency_call_failed",
                    outcome = "failure",
                    dependency = "keycloak",
                    duration_ms = started.elapsed().as_millis() as i64,
                    error_code = "idp_unreachable",
                    error = %err,
                    "Keycloak was unreachable during token refresh; the session is kept"
                );
                RefreshError::Unavailable
            })?;

        if !response.status().is_success() {
            let status = response.status();
            if status == reqwest::StatusCode::BAD_REQUEST
                || status == reqwest::StatusCode::UNAUTHORIZED
            {
                tracing::warn!(
                    event = "token_refresh_failed",
                    outcome = "failure",
                    error_code = "idp_rejected",
                    idp_status = status.as_u16(),
                    "Token refresh rejected by the IdP; treating the session as expired"
                );
                return Err(RefreshError::Rejected);
            }
            tracing::error!(
                event = "dependency_call_failed",
                outcome = "failure",
                dependency = "keycloak",
                duration_ms = started.elapsed().as_millis() as i64,
                error_code = %format!("idp_status_{}", status.as_u16()),
                "Keycloak failed during token refresh; the session is kept"
            );
            return Err(RefreshError::Unavailable);
        }

        let payload = response.json::<TokenResponse>().await.map_err(|err| {
            tracing::error!(
                event = "dependency_call_failed",
                outcome = "failure",
                dependency = "keycloak",
                duration_ms = started.elapsed().as_millis() as i64,
                error_code = "idp_bad_response",
                error = %err,
                "Keycloak returned an unreadable token response; the session is kept"
            );
            RefreshError::Unavailable
        })?;

        if payload.access_token.is_empty() {
            tracing::error!(
                event = "dependency_call_failed",
                outcome = "failure",
                dependency = "keycloak",
                duration_ms = started.elapsed().as_millis() as i64,
                error_code = "idp_bad_response",
                "Keycloak returned a token response without an access token; the session is kept"
            );
            return Err(RefreshError::Unavailable);
        }

        let now = Utc::now();
        data.access_token = payload.access_token;
        if let Some(refresh_token) = payload.refresh_token.filter(|value| !value.is_empty()) {
            data.refresh_token = refresh_token;
        }
        if let Some(id_token) = payload.id_token.filter(|value| !value.is_empty()) {
            data.id_token = id_token;
        }
        data.expires_at = now + TimeDelta::seconds(payload.expires_in.unwrap_or(300).max(1));
        data.updated_at = now;
        Ok(data)
    }

    pub async fn logout(&self, refresh_token: &str) {
        if refresh_token.is_empty() {
            return;
        }
        let response = self
            .http
            .post(&self.logout_url)
            .form(&[
                ("client_id", self.config.oidc_client_id.as_str()),
                ("client_secret", self.config.oidc_client_secret.as_str()),
                ("refresh_token", refresh_token),
            ])
            .send()
            .await;
        match response {
            Ok(response) if response.status().is_success() => {}
            Ok(response) => {
                tracing::warn!(
                    event = "idp_logout_failed",
                    outcome = "failure",
                    error_code = "idp_rejected",
                    idp_status = response.status().as_u16(),
                    "IdP logout returned a non-success status; local session destroyed anyway"
                );
            }
            Err(err) => {
                tracing::warn!(
                    event = "idp_logout_failed",
                    outcome = "failure",
                    error_code = "idp_unreachable",
                    error = %err,
                    "IdP logout failed; local session destroyed anyway"
                );
            }
        }
    }

    async fn validate_id_token(&self, raw: &str) -> anyhow::Result<String> {
        let header = decode_header(raw)?;
        let kid = header.kid.context("ID token has no kid")?;
        let key = self.key(&kid).await?;
        let mut validation = Validation::new(Algorithm::RS256);
        validation.set_issuer(std::slice::from_ref(&self.config.oidc_issuer_uri));
        validation.set_audience(std::slice::from_ref(&self.config.oidc_client_id));
        let token = decode::<IdClaims>(raw, &key, &validation)?;
        let username = token
            .claims
            .preferred_username
            .filter(|value| !value.is_empty())
            .unwrap_or(token.claims.sub);
        if username.is_empty() {
            return Err(anyhow!("ID token missing subject"));
        }
        Ok(username)
    }

    async fn key(&self, kid: &str) -> anyhow::Result<DecodingKey> {
        let mut state = self.jwks.lock().await;
        if let Some(key) = state.keys.get(kid) {
            return Ok(key.clone());
        }
        if state
            .last_refresh
            .is_some_and(|last| last.elapsed() < JWKS_REFRESH_COOLDOWN)
        {
            return Err(anyhow!("unknown signing key {kid}"));
        }
        self.refresh_keys_locked(&mut state).await?;
        state
            .keys
            .get(kid)
            .cloned()
            .ok_or_else(|| anyhow!("unknown signing key {kid}"))
    }

    async fn refresh_keys_locked(&self, state: &mut JwksState) -> anyhow::Result<()> {
        state.last_refresh = Some(Instant::now());
        let started = Instant::now();
        let document = self
            .http
            .get(&self.jwks_url)
            .send()
            .await
            .and_then(|response| response.error_for_status())
            .context("fetch JWKS")
            .map_err(|err| {
                tracing::error!(
                    event = "dependency_call_failed",
                    outcome = "failure",
                    dependency = "keycloak",
                    duration_ms = started.elapsed().as_millis() as i64,
                    error_code = "jwks_fetch_failed",
                    error = %err,
                    "Fetching the JWKS from the IdP failed"
                );
                err
            })?
            .json::<JwksDocument>()
            .await?;
        let mut keys = HashMap::new();
        for jwk in document.keys {
            if jwk.kty != "RSA" {
                continue;
            }
            if let Ok(key) = DecodingKey::from_rsa_components(&jwk.n, &jwk.e) {
                keys.insert(jwk.kid, key);
            }
        }
        if keys.is_empty() {
            return Err(anyhow!(
                "the JWKS document contains no usable RSA signing keys"
            ));
        }
        state.keys = keys;
        Ok(())
    }
}

pub fn new_opaque_id() -> String {
    let bytes: [u8; 32] = rand::random();
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

pub fn new_code_verifier() -> String {
    let bytes: [u8; 32] = rand::random();
    URL_SAFE_NO_PAD.encode(bytes)
}

fn code_challenge(verifier: &str) -> String {
    URL_SAFE_NO_PAD.encode(Sha256::digest(verifier.as_bytes()))
}

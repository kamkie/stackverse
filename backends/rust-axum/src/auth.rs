use std::collections::HashMap;
use std::sync::Arc;
use std::time::{Duration, Instant};

use anyhow::{Context, anyhow};
use axum::body::Body;
use axum::extract::State;
use axum::http::{HeaderMap, Request, StatusCode, header};
use axum::middleware::Next;
use axum::response::Response;
use jsonwebtoken::{Algorithm, DecodingKey, Validation, decode, decode_header};
use serde::Deserialize;
use sqlx::Row;
use tokio::sync::Mutex;

use crate::AppState;
use crate::config::Config;
use crate::error;

const REFRESH_COOLDOWN: Duration = Duration::from_secs(30);

#[derive(Debug, Clone)]
pub struct Identity {
    pub username: String,
    pub name: Option<String>,
    pub email: Option<String>,
    pub roles: Vec<String>,
}

impl Identity {
    pub fn has_role(&self, role: &str) -> bool {
        self.roles.iter().any(|candidate| candidate == role)
    }
}

#[derive(Clone)]
pub struct JwksCache {
    config: Arc<Config>,
    http: reqwest::Client,
    state: Arc<Mutex<JwksState>>,
}

#[derive(Default)]
struct JwksState {
    resolved_uri: Option<String>,
    keys: HashMap<String, DecodingKey>,
    last_refresh: Option<Instant>,
}

#[derive(Deserialize)]
struct DiscoveryDocument {
    jwks_uri: String,
}

#[derive(Deserialize)]
struct JwksDocument {
    keys: Vec<Jwk>,
}

#[derive(Deserialize)]
struct Jwk {
    kid: String,
    kty: String,
    #[serde(default)]
    #[allow(dead_code)]
    use_: String,
    n: String,
    e: String,
}

#[derive(Debug, Deserialize)]
struct Claims {
    preferred_username: String,
    #[serde(default)]
    name: Option<String>,
    #[serde(default)]
    email: Option<String>,
    #[serde(default)]
    realm_access: Option<RealmAccess>,
}

#[derive(Debug, Deserialize)]
struct RealmAccess {
    #[serde(default)]
    roles: Vec<String>,
}

impl JwksCache {
    pub fn new(config: Arc<Config>, http: reqwest::Client) -> Self {
        Self {
            config,
            http,
            state: Arc::new(Mutex::new(JwksState::default())),
        }
    }

    async fn key(&self, kid: &str) -> anyhow::Result<DecodingKey> {
        let mut state = self.state.lock().await;
        if let Some(key) = state.keys.get(kid) {
            return Ok(key.clone());
        }
        if state
            .last_refresh
            .is_some_and(|last| last.elapsed() < REFRESH_COOLDOWN)
        {
            return Err(anyhow!("unknown signing key {kid}"));
        }
        self.refresh_locked(&mut state).await?;
        state
            .keys
            .get(kid)
            .cloned()
            .ok_or_else(|| anyhow!("unknown signing key {kid}"))
    }

    async fn refresh_locked(&self, state: &mut JwksState) -> anyhow::Result<()> {
        state.last_refresh = Some(Instant::now());
        let uri = match &state.resolved_uri {
            Some(uri) => uri.clone(),
            None => {
                let discovered = self.discover_uri().await?;
                state.resolved_uri = Some(discovered.clone());
                discovered
            }
        };
        let started = Instant::now();
        let document = self
            .http
            .get(&uri)
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

    async fn discover_uri(&self) -> anyhow::Result<String> {
        if let Some(uri) = &self.config.oidc_jwks_uri {
            return Ok(uri.clone());
        }
        let started = Instant::now();
        let uri = format!(
            "{}/.well-known/openid-configuration",
            self.config.oidc_issuer_uri
        );
        let document = self
            .http
            .get(uri)
            .send()
            .await
            .and_then(|response| response.error_for_status())
            .context("discover OIDC configuration")
            .map_err(|err| {
                tracing::error!(
                    event = "dependency_call_failed",
                    outcome = "failure",
                    dependency = "keycloak",
                    duration_ms = started.elapsed().as_millis() as i64,
                    error_code = "oidc_discovery_failed",
                    error = %err,
                    "OIDC discovery against the issuer failed"
                );
                err
            })?
            .json::<DiscoveryDocument>()
            .await?;
        if document.jwks_uri.is_empty() {
            return Err(anyhow!("the discovery document carries no jwks_uri"));
        }
        Ok(document.jwks_uri)
    }
}

pub async fn authenticate(
    State(state): State<AppState>,
    mut request: Request<Body>,
    next: Next,
) -> Response {
    if !request.uri().path().starts_with("/api/") {
        return next.run(request).await;
    }

    let headers = request.headers().clone();
    let uri = request.uri().clone();
    let Some(raw) = bearer_token(&headers) else {
        return next.run(request).await;
    };

    match validate_token(&state, raw).await {
        Ok(identity) => match record_seen(&state, &identity.username).await {
            Ok(account) if account.status == "blocked" => {
                tracing::warn!(
                    event = "blocked_user_rejected",
                    outcome = "denied",
                    actor = %identity.username,
                    "Refused a request from a blocked account"
                );
                return error::problem_key(
                    &state,
                    &headers,
                    &uri,
                    StatusCode::FORBIDDEN,
                    "Forbidden",
                    "error.account.blocked",
                )
                .await;
            }
            Ok(_) => {
                request.extensions_mut().insert(identity);
                next.run(request).await
            }
            Err(err) => error::internal_error(&state, &headers, &uri, err).await,
        },
        Err(_) => {
            tracing::info!(
                event = "jwt_validation_failed",
                outcome = "failure",
                error_code = "invalid_token",
                "Rejected a bearer token"
            );
            error::problem(
                &state,
                &headers,
                &uri,
                StatusCode::UNAUTHORIZED,
                "Unauthorized",
                Some("Missing or invalid bearer token.".to_string()),
            )
            .await
        }
    }
}

fn bearer_token(headers: &HeaderMap) -> Option<&str> {
    let value = headers.get(header::AUTHORIZATION)?.to_str().ok()?;
    value.strip_prefix("Bearer ")
}

async fn validate_token(state: &AppState, raw: &str) -> anyhow::Result<Identity> {
    let header = decode_header(raw)?;
    let kid = header.kid.ok_or_else(|| anyhow!("token has no kid"))?;
    let key = state.jwks.key(&kid).await?;
    let mut validation = Validation::new(Algorithm::RS256);
    validation.set_issuer(&[state.config.oidc_issuer_uri.clone()]);
    validation.set_audience(&[Config::AUDIENCE]);
    let token = decode::<Claims>(raw, &key, &validation)?;
    if token.claims.preferred_username.is_empty() {
        return Err(anyhow!("missing preferred_username"));
    }
    Ok(Identity {
        username: token.claims.preferred_username,
        name: token.claims.name,
        email: token.claims.email,
        roles: token
            .claims
            .realm_access
            .map(|realm| realm.roles)
            .unwrap_or_default(),
    })
}

struct AccountStatus {
    status: String,
}

async fn record_seen(state: &AppState, username: &str) -> anyhow::Result<AccountStatus> {
    let now = crate::error::now_utc();
    let row = sqlx::query(
        r#"insert into user_accounts (username, first_seen, last_seen, status)
           values ($1, $2, $2, 'active')
           on conflict (username) do update set last_seen = excluded.last_seen
           returning status"#,
    )
    .bind(username)
    .bind(now)
    .fetch_one(&state.pool)
    .await?;
    Ok(AccountStatus {
        status: row.try_get("status")?,
    })
}

pub mod config;
pub mod logging;
pub mod oidc;
pub mod problems;
pub mod proxy;
pub mod security;
pub mod session;

use std::collections::HashMap;
use std::path::{Component, Path, PathBuf};
use std::sync::Arc;

use axum::body::Body;
use axum::extract::State;
use axum::http::{HeaderMap, Method, Request, StatusCode, header};
use axum::response::{IntoResponse, Response};
use axum::routing::{any, get, post};
use axum::{Json, Router, middleware};
use chrono::{TimeDelta, Utc};
use serde::Serialize;
use tower_http::trace::TraceLayer;

use crate::config::Config;
use crate::oidc::{OidcClient, RefreshError, new_code_verifier, new_opaque_id};
use crate::security::{
    LOGIN_STATE_COOKIE, SESSION_COOKIE, append_set_cookie, clear_login_state_cookie,
    clear_session_cookie, cookie_value, cross_origin_problem, csrf_problem, expected_origin,
    login_state_cookie, same_origin_state_change_allowed, session_cookie, valid_csrf,
};
use crate::session::{DynSessionStore, OAuthState, SESSION_TTL, STATE_TTL, SessionData};

const REFRESH_SKEW_SECONDS: i64 = 30;
const PLACEHOLDER_INDEX: &str = include_str!("static/index.html");

#[derive(Clone)]
pub struct AppState {
    pub config: Arc<Config>,
    pub expected_origin: String,
    pub store: DynSessionStore,
    pub oidc: Arc<OidcClient>,
    pub http: reqwest::Client,
}

impl AppState {
    pub fn new(
        config: Arc<Config>,
        store: DynSessionStore,
        oidc: Arc<OidcClient>,
        http: reqwest::Client,
    ) -> Self {
        let expected_origin = expected_origin(&config.public_url);
        Self {
            config,
            expected_origin,
            store,
            oidc,
            http,
        }
    }
}

pub fn app(state: AppState) -> Router {
    Router::new()
        .route("/healthz", get(healthz))
        .route("/readyz", get(readyz))
        .route("/auth/login", get(login))
        .route("/auth/callback", get(callback))
        .route("/auth/logout", post(logout))
        .route("/auth/session", get(auth_session))
        .route("/api", any(api))
        .route("/api/{*rest}", any(api))
        .fallback(frontend)
        .with_state(state.clone())
        .layer(middleware::from_fn_with_state(
            state.clone(),
            security::csrf_cookie,
        ))
        .layer(middleware::from_fn_with_state(
            state.clone(),
            security::security_headers,
        ))
        .layer(TraceLayer::new_for_http())
}

async fn healthz() -> StatusCode {
    StatusCode::OK
}

async fn readyz(State(state): State<AppState>) -> StatusCode {
    match state.store.ping().await {
        Ok(()) => StatusCode::OK,
        Err(err) => {
            log_dependency_failure("redis", "redis_ping_failed", &err);
            StatusCode::SERVICE_UNAVAILABLE
        }
    }
}

async fn login(State(state): State<AppState>) -> Response {
    let login_state = new_opaque_id();
    let verifier = new_code_verifier();
    let oauth_state = OAuthState {
        code_verifier: verifier.clone(),
        created_at: Utc::now(),
    };
    if let Err(err) = state
        .store
        .save_oauth_state(&login_state, &oauth_state, STATE_TTL)
        .await
    {
        log_dependency_failure("redis", "redis_write_failed", &err);
        return problems::problem(
            StatusCode::SERVICE_UNAVAILABLE,
            "Service Unavailable",
            "Session storage is temporarily unavailable.",
        );
    }

    let redirect = match state.oidc.authorization_redirect(&login_state, &verifier) {
        Ok(redirect) => redirect,
        Err(err) => {
            tracing::error!(
                event = "dependency_call_failed",
                outcome = "failure",
                dependency = "keycloak",
                error_code = "authorization_url_failed",
                error = %err,
                "Could not create the authorization redirect"
            );
            return problems::problem(
                StatusCode::SERVICE_UNAVAILABLE,
                "Service Unavailable",
                "Authentication is temporarily unavailable; please retry.",
            );
        }
    };
    let mut response = redirect_response(&redirect);
    append_set_cookie(
        response.headers_mut(),
        &login_state_cookie(&login_state, state.config.cookies_secure()),
    );
    response
}

async fn callback(State(state): State<AppState>, request: Request<Body>) -> Response {
    let secure = state.config.cookies_secure();
    let params = query_params(request.uri().query().unwrap_or_default());
    let failed = params.contains_key("error")
        || params.get("code").is_none_or(String::is_empty)
        || params.get("state").is_none_or(String::is_empty);
    if failed {
        tracing::info!(
            event = "oidc_callback_completed",
            outcome = "failure",
            error_code = "remote_failure",
            "Authorization code flow failed"
        );
        return redirect_home_clearing_login_state(secure);
    }

    let query_state = params.get("state").expect("checked above");
    let cookie_state = cookie_value(request.headers(), LOGIN_STATE_COOKIE);
    if cookie_state
        .as_deref()
        .is_none_or(|cookie| !security::constant_time_eq(cookie.as_bytes(), query_state.as_bytes()))
    {
        tracing::info!(
            event = "oidc_callback_completed",
            outcome = "failure",
            error_code = "invalid_state",
            "Authorization code flow failed"
        );
        return redirect_home_clearing_login_state(secure);
    }

    let stored = match state.store.consume_oauth_state(query_state).await {
        Ok(Some(stored)) => stored,
        Ok(None) => {
            tracing::info!(
                event = "oidc_callback_completed",
                outcome = "failure",
                error_code = "invalid_state",
                "Authorization code flow failed"
            );
            return redirect_home_clearing_login_state(secure);
        }
        Err(err) => {
            log_dependency_failure("redis", "redis_read_failed", &err);
            tracing::info!(
                event = "oidc_callback_completed",
                outcome = "failure",
                error_code = "state_store_unavailable",
                "Authorization code flow failed"
            );
            return redirect_home_clearing_login_state(secure);
        }
    };

    let session = match state
        .oidc
        .exchange(
            params.get("code").expect("checked above"),
            &stored.code_verifier,
        )
        .await
    {
        Ok(session) => session,
        Err(err) => {
            tracing::info!(
                event = "oidc_callback_completed",
                outcome = "failure",
                error_code = "token_exchange_failed",
                error = %err,
                "Authorization code flow failed"
            );
            return redirect_home_clearing_login_state(secure);
        }
    };

    let session_key = new_opaque_id();
    if let Err(err) = state
        .store
        .save_session(&session_key, &session, SESSION_TTL)
        .await
    {
        log_dependency_failure("redis", "redis_write_failed", &err);
        return redirect_home_clearing_login_state(secure);
    }

    tracing::info!(
        event = "oidc_callback_completed",
        outcome = "success",
        actor = %session.username,
        "Authorization code flow completed"
    );
    tracing::info!(
        event = "session_created",
        outcome = "success",
        actor = %session.username,
        "Session ticket stored in Redis, cookie issued"
    );

    let mut response = redirect_response("/");
    append_set_cookie(
        response.headers_mut(),
        &clear_login_state_cookie(state.config.cookies_secure()),
    );
    append_set_cookie(
        response.headers_mut(),
        &session_cookie(&session_key, state.config.cookies_secure()),
    );
    response
}

async fn logout(State(state): State<AppState>, headers: HeaderMap) -> Response {
    let secure = state.config.cookies_secure();
    let mut session_for_logout = None;
    if let Some(session_key) = cookie_value(&headers, SESSION_COOKIE) {
        match state.store.load_session(&session_key).await {
            Ok(session) => session_for_logout = session,
            Err(err) => log_dependency_failure("redis", "redis_read_failed", &err),
        }
        if let Err(err) = state.store.delete_session(&session_key).await {
            log_dependency_failure("redis", "redis_delete_failed", &err);
        }
    }

    let mut response = StatusCode::NO_CONTENT.into_response();
    append_set_cookie(response.headers_mut(), &clear_session_cookie(secure));
    if let Some(session) = session_for_logout {
        tracing::info!(
            event = "session_destroyed",
            outcome = "success",
            reason = "logout",
            actor = %session.username,
            "Session destroyed by user logout"
        );
        state.oidc.logout(&session.refresh_token).await;
    }
    response
}

async fn auth_session(State(state): State<AppState>, headers: HeaderMap) -> Response {
    #[derive(Serialize)]
    struct SessionResponse<'a> {
        authenticated: bool,
        #[serde(skip_serializing_if = "Option::is_none")]
        username: Option<&'a str>,
    }

    let Some(session_key) = cookie_value(&headers, SESSION_COOKIE) else {
        return Json(SessionResponse {
            authenticated: false,
            username: None,
        })
        .into_response();
    };
    match state.store.load_session(&session_key).await {
        Ok(Some(session)) => Json(SessionResponse {
            authenticated: true,
            username: Some(&session.username),
        })
        .into_response(),
        Ok(None) => Json(SessionResponse {
            authenticated: false,
            username: None,
        })
        .into_response(),
        Err(err) => {
            log_dependency_failure("redis", "redis_read_failed", &err);
            Json(SessionResponse {
                authenticated: false,
                username: None,
            })
            .into_response()
        }
    }
}

async fn api(State(state): State<AppState>, request: Request<Body>) -> Response {
    let method = request.method().clone();
    let path = request.uri().path().to_string();
    let headers = request.headers().clone();

    if !same_origin_state_change_allowed(&headers, &method, &path, &state.expected_origin) {
        tracing::info!(
            event = "csrf_validation_failed",
            outcome = "denied",
            method = %sanitize(method.as_str(), 32),
            path = %sanitize(&path, 200),
            "Rejected a cross-origin state-changing /api request"
        );
        return cross_origin_problem();
    }
    if !valid_csrf(&headers, &method) {
        tracing::info!(
            event = "csrf_validation_failed",
            outcome = "denied",
            method = %sanitize(method.as_str(), 32),
            path = %sanitize(&path, 200),
            "Rejected a state-changing /api request without a matching CSRF header"
        );
        return csrf_problem();
    }

    let mut access_token = None;
    let mut clear_session = false;
    let mut refresh_cookie = None;
    if let Some(session_key) = cookie_value(&headers, SESSION_COOKIE) {
        match state.store.load_session(&session_key).await {
            Ok(Some(session)) => match ensure_access_token(&state, &session_key, session).await {
                Ok(TokenDecision::Token(token)) => access_token = Some(token),
                Ok(TokenDecision::Refreshed(session)) => {
                    access_token = Some(session.access_token.clone());
                    refresh_cookie = Some(session_key);
                }
                Ok(TokenDecision::Anonymous) => clear_session = true,
                Err(ApiSessionError::Store(err)) => {
                    log_dependency_failure("redis", "redis_write_failed", &err);
                    return problems::problem(
                        StatusCode::SERVICE_UNAVAILABLE,
                        "Service Unavailable",
                        "Session storage is temporarily unavailable.",
                    );
                }
                Err(ApiSessionError::IdpUnavailable) => {
                    return problems::problem(
                        StatusCode::SERVICE_UNAVAILABLE,
                        "Service Unavailable",
                        "Authentication is temporarily unavailable; please retry.",
                    );
                }
            },
            Ok(None) => {}
            Err(err) => {
                log_dependency_failure("redis", "redis_read_failed", &err);
                return problems::problem(
                    StatusCode::SERVICE_UNAVAILABLE,
                    "Service Unavailable",
                    "Session storage is temporarily unavailable.",
                );
            }
        }
    }

    let mut response = proxy::proxy(
        &state,
        request,
        &state.config.backend_url,
        "backend",
        true,
        access_token.as_deref(),
    )
    .await;
    if clear_session {
        append_set_cookie(
            response.headers_mut(),
            &clear_session_cookie(state.config.cookies_secure()),
        );
    } else if let Some(session_key) = refresh_cookie {
        append_set_cookie(
            response.headers_mut(),
            &session_cookie(&session_key, state.config.cookies_secure()),
        );
    }
    response
}

async fn frontend(State(state): State<AppState>, request: Request<Body>) -> Response {
    if let Some(frontend_url) = &state.config.frontend_url {
        return proxy::proxy(&state, request, frontend_url, "frontend", false, None).await;
    }
    if !matches!(*request.method(), Method::GET | Method::HEAD) {
        return StatusCode::NOT_FOUND.into_response();
    }
    if let Some(spa_root) = &state.config.spa_root {
        return serve_spa_file(spa_root, request.uri().path()).await;
    }
    html_response(PLACEHOLDER_INDEX)
}

enum TokenDecision {
    Token(String),
    Refreshed(SessionData),
    Anonymous,
}

enum ApiSessionError {
    Store(anyhow::Error),
    IdpUnavailable,
}

async fn ensure_access_token(
    state: &AppState,
    session_key: &str,
    session: SessionData,
) -> Result<TokenDecision, ApiSessionError> {
    if session.access_token.is_empty() {
        destroy_after_refresh_rejection(state, session_key, &session).await;
        return Ok(TokenDecision::Anonymous);
    }
    if session.expires_at - Utc::now() > TimeDelta::seconds(REFRESH_SKEW_SECONDS) {
        return Ok(TokenDecision::Token(session.access_token));
    }
    match state.oidc.refresh(session.clone()).await {
        Ok(refreshed) => {
            state
                .store
                .save_session(session_key, &refreshed, SESSION_TTL)
                .await
                .map_err(ApiSessionError::Store)?;
            Ok(TokenDecision::Refreshed(refreshed))
        }
        Err(RefreshError::Rejected) => {
            destroy_after_refresh_rejection(state, session_key, &session).await;
            Ok(TokenDecision::Anonymous)
        }
        Err(RefreshError::Unavailable) => Err(ApiSessionError::IdpUnavailable),
    }
}

async fn destroy_after_refresh_rejection(
    state: &AppState,
    session_key: &str,
    session: &SessionData,
) {
    if let Err(err) = state.store.delete_session(session_key).await {
        log_dependency_failure("redis", "redis_delete_failed", &err);
    }
    tracing::info!(
        event = "session_destroyed",
        outcome = "success",
        reason = "token_refresh_failed",
        actor = %session.username,
        "Session destroyed after a failed token refresh; request degraded to anonymous"
    );
}

async fn serve_spa_file(root: &Path, path: &str) -> Response {
    let requested = safe_spa_path(root, path);
    let path = if tokio::fs::metadata(&requested)
        .await
        .is_ok_and(|metadata| metadata.is_file())
    {
        requested
    } else {
        root.join("index.html")
    };
    match tokio::fs::read(&path).await {
        Ok(bytes) => {
            let content_type = mime_guess::from_path(&path).first_or_octet_stream();
            Response::builder()
                .status(StatusCode::OK)
                .header(header::CONTENT_TYPE, content_type.as_ref())
                .body(Body::from(bytes))
                .expect("valid static response")
        }
        Err(_) => html_response(PLACEHOLDER_INDEX),
    }
}

fn safe_spa_path(root: &Path, path: &str) -> PathBuf {
    let mut relative = PathBuf::new();
    for component in Path::new(path.trim_start_matches('/')).components() {
        if let Component::Normal(segment) = component {
            relative.push(segment);
        }
    }
    if relative.as_os_str().is_empty() {
        relative.push("index.html");
    }
    root.join(relative)
}

fn html_response(html: &'static str) -> Response {
    Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, "text/html; charset=utf-8")
        .body(Body::from(html))
        .expect("valid HTML response")
}

fn redirect_home_clearing_login_state(secure: bool) -> Response {
    let mut response = redirect_response("/");
    append_set_cookie(response.headers_mut(), &clear_login_state_cookie(secure));
    response
}

fn redirect_response(location: &str) -> Response {
    Response::builder()
        .status(StatusCode::FOUND)
        .header(header::LOCATION, location)
        .body(Body::empty())
        .expect("valid redirect")
}

fn query_params(raw: &str) -> HashMap<String, String> {
    url::form_urlencoded::parse(raw.as_bytes())
        .into_owned()
        .collect()
}

fn log_dependency_failure(dependency: &str, error_code: &str, err: &anyhow::Error) {
    tracing::error!(
        event = "dependency_call_failed",
        outcome = "failure",
        dependency,
        error_code,
        error = %err,
        "Gateway dependency call failed"
    );
}

fn sanitize(value: &str, max_len: usize) -> String {
    let mut output = String::new();
    for ch in value.replace("\r\n", "\n").chars() {
        if output.len() >= max_len {
            output.push_str("...");
            break;
        }
        if ch == '\n' || ch == '\r' {
            output.push_str("\\n");
        } else if !ch.is_control() {
            output.push(ch);
        }
    }
    output
}

#[cfg(test)]
mod tests {
    use std::path::Path;

    use super::{query_params, safe_spa_path, sanitize};

    #[test]
    fn query_params_parse_callback_values() {
        let params = query_params("code=ok&state=abc");
        assert_eq!(params.get("code").map(String::as_str), Some("ok"));
        assert_eq!(params.get("state").map(String::as_str), Some("abc"));
    }

    #[test]
    fn safe_spa_path_ignores_parent_components() {
        assert_eq!(
            safe_spa_path(Path::new("/app/spa"), "/../../secret.txt"),
            Path::new("/app/spa/secret.txt")
        );
    }

    #[test]
    fn sanitize_removes_control_characters_and_caps_length() {
        assert_eq!(sanitize("abc\r\nxyz", 20), "abc\\nxyz");
        assert_eq!(sanitize("abcdef", 3), "abc...");
    }
}

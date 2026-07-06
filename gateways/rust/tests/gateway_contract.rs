use std::sync::Arc;

use axum::body::{Body, to_bytes};
use axum::extract::State;
use axum::http::{HeaderMap, Method, Request, StatusCode, header};
use axum::response::{IntoResponse, Response};
use axum::routing::any;
use axum::{Json, Router};
use chrono::{TimeDelta, Utc};
use jsonwebtoken::{Algorithm, EncodingKey, Header, encode};
use rsa::pkcs8::{EncodePrivateKey, LineEnding};
use rsa::rand_core::OsRng;
use rsa::traits::PublicKeyParts;
use rsa::{RsaPrivateKey, RsaPublicKey};
use serde_json::json;
use stackverse_gateway_rust::config::Config;
use stackverse_gateway_rust::oidc::OidcClient;
use stackverse_gateway_rust::security::{
    CONTENT_SECURITY_POLICY, CSRF_COOKIE, CSRF_HEADER, LOGIN_STATE_COOKIE, SESSION_COOKIE,
    STRICT_TRANSPORT_SECURITY,
};
use stackverse_gateway_rust::session::{MemorySessionStore, SessionData, SessionStore};
use stackverse_gateway_rust::{AppState, app};
use tokio::net::TcpListener;
use tokio::sync::Mutex;
use tower::ServiceExt;

#[tokio::test]
async fn anonymous_api_requests_relay_without_bearer_token() {
    let harness = Harness::new(IdpBehavior::default()).await;
    let response = harness
        .request(Method::GET, "/api/v2/bookmarks?visibility=public")
        .header(header::AUTHORIZATION, "Bearer forged")
        .header(header::COOKIE, "unrelated=cookie")
        .send()
        .await;

    assert_eq!(response.status(), StatusCode::OK);
    let record = harness.backend.record().await;
    assert_eq!(record.authorization, "");
    assert_eq!(record.cookie, "");
}

#[tokio::test]
async fn authenticated_api_requests_relay_bearer_token() {
    let harness = Harness::new(IdpBehavior::default()).await;
    harness
        .store
        .put_session("s1", future_session("live-access", "refresh"))
        .await;

    let response = harness
        .request(Method::GET, "/api/v1/me")
        .header(header::AUTHORIZATION, "Bearer forged")
        .session_cookie("s1")
        .send()
        .await;

    assert_eq!(response.status(), StatusCode::OK);
    let record = harness.backend.record().await;
    assert_eq!(record.authorization, "Bearer live-access");
    assert_eq!(record.cookie, "");
}

#[tokio::test]
async fn state_changing_api_requires_csrf_and_same_origin_signals() {
    let harness = Harness::new(IdpBehavior::default()).await;

    let missing = harness
        .request(Method::POST, "/api/v1/bookmarks")
        .json_body(r#"{"url":"https://example.com","title":"Example"}"#)
        .send()
        .await;
    assert_eq!(missing.status(), StatusCode::FORBIDDEN);
    assert_content_type(&missing, "application/problem+json");

    let issued = harness.request(Method::GET, "/auth/session").send().await;
    let xsrf = set_cookie_value(issued.headers(), CSRF_COOKIE).expect("XSRF-TOKEN");

    let allowed = harness
        .request(Method::POST, "/api/v1/bookmarks")
        .header(header::COOKIE, format!("{CSRF_COOKIE}={xsrf}"))
        .header(CSRF_HEADER, xsrf.clone())
        .header(header::ORIGIN, "http://localhost:8000")
        .json_body(r#"{"url":"https://example.com","title":"Example"}"#)
        .send()
        .await;
    assert_eq!(allowed.status(), StatusCode::OK);
    let record = harness.backend.record().await;
    assert_eq!(record.csrf, "");

    let rejected = harness
        .request(Method::POST, "/api/v1/bookmarks")
        .header(header::COOKIE, format!("{CSRF_COOKIE}={xsrf}"))
        .header(CSRF_HEADER, xsrf)
        .header("sec-fetch-site", "same-site")
        .json_body(r#"{"url":"https://example.com","title":"Example"}"#)
        .send()
        .await;
    assert_eq!(rejected.status(), StatusCode::FORBIDDEN);
    let body = response_body(rejected).await;
    assert!(body.contains("Cross-origin state-changing requests are not supported."));
}

#[tokio::test]
async fn security_headers_are_scoped_without_changing_api_semantics() {
    let harness = Harness::new(IdpBehavior::default()).await;

    let session = harness.request(Method::GET, "/auth/session").send().await;
    assert_document_headers(&session, false);

    let api = harness
        .request(Method::GET, "/api/v1/messages/bundle")
        .send()
        .await;
    assert_api_headers(&api, false);
    assert_eq!(
        api.headers().get(header::CACHE_CONTROL).unwrap(),
        "no-cache"
    );
    assert_eq!(api.headers().get(header::ETAG).unwrap(), "\"bundle-v1\"");
}

#[tokio::test]
async fn login_redirects_with_code_flow_and_pkce() {
    let harness = Harness::new(IdpBehavior::default()).await;
    let response = harness.request(Method::GET, "/auth/login").send().await;

    assert_eq!(response.status(), StatusCode::FOUND);
    let location = response
        .headers()
        .get(header::LOCATION)
        .unwrap()
        .to_str()
        .unwrap();
    let parsed = url::Url::parse(location).unwrap();
    assert_eq!(
        parsed.path(),
        "/realms/stackverse/protocol/openid-connect/auth"
    );
    let query = parsed
        .query_pairs()
        .collect::<std::collections::HashMap<_, _>>();
    assert_eq!(query.get("response_type").map(|v| v.as_ref()), Some("code"));
    assert_eq!(
        query.get("redirect_uri").map(|v| v.as_ref()),
        Some("http://localhost:8000/auth/callback")
    );
    assert_eq!(
        query.get("code_challenge_method").map(|v| v.as_ref()),
        Some("S256")
    );
    assert!(
        query
            .get("code_challenge")
            .is_some_and(|value| !value.is_empty())
    );
    assert!(set_cookie_value(response.headers(), LOGIN_STATE_COOKIE).is_some());
}

#[tokio::test]
async fn failed_callback_redirects_home_without_session() {
    let harness = Harness::new(IdpBehavior::default()).await;
    let response = harness
        .request(
            Method::GET,
            "/auth/callback?error=access_denied&state=whatever",
        )
        .send()
        .await;

    assert_eq!(response.status(), StatusCode::FOUND);
    assert_eq!(response.headers().get(header::LOCATION).unwrap(), "/");
    assert!(set_cookie_value(response.headers(), SESSION_COOKIE).is_none());
}

#[tokio::test]
async fn successful_callback_creates_redis_backed_session() {
    let harness = Harness::new(IdpBehavior::default()).await;
    let login = harness.request(Method::GET, "/auth/login").send().await;
    let location = login
        .headers()
        .get(header::LOCATION)
        .unwrap()
        .to_str()
        .unwrap();
    let state = url::Url::parse(location)
        .unwrap()
        .query_pairs()
        .find(|(name, _)| name == "state")
        .map(|(_, value)| value.to_string())
        .expect("state");
    let login_cookie = set_cookie_value(login.headers(), LOGIN_STATE_COOKIE).unwrap();

    let callback = harness
        .request(
            Method::GET,
            &format!("/auth/callback?code=ok&state={state}"),
        )
        .header(
            header::COOKIE,
            format!("{LOGIN_STATE_COOKIE}={login_cookie}"),
        )
        .send()
        .await;
    assert_eq!(callback.status(), StatusCode::FOUND);
    let session_cookie = set_cookie_value(callback.headers(), SESSION_COOKIE).unwrap();

    let session = harness
        .request(Method::GET, "/auth/session")
        .header(header::COOKIE, format!("{SESSION_COOKIE}={session_cookie}"))
        .send()
        .await;
    let body = response_body(session).await;
    assert!(body.contains(r#""authenticated":true"#));
    assert!(body.contains(r#""username":"demo""#));
}

#[tokio::test]
async fn refresh_rejected_destroys_session_and_relays_anonymous() {
    let harness = Harness::new(IdpBehavior {
        refresh_status: StatusCode::BAD_REQUEST,
        ..Default::default()
    })
    .await;
    harness.store.put_session("s1", expired_session()).await;

    let response = harness
        .request(Method::GET, "/api/v1/bookmarks")
        .session_cookie("s1")
        .send()
        .await;

    assert_eq!(response.status(), StatusCode::OK);
    let record = harness.backend.record().await;
    assert_eq!(record.authorization, "");
    assert!(!harness.store.has_session("s1").await);
    assert!(clears_cookie(response.headers(), SESSION_COOKIE));
}

#[tokio::test]
async fn idp_outage_during_refresh_keeps_session_and_returns_503() {
    let harness = Harness::new(IdpBehavior {
        refresh_status: StatusCode::SERVICE_UNAVAILABLE,
        ..Default::default()
    })
    .await;
    harness.store.put_session("s1", expired_session()).await;

    let response = harness
        .request(Method::GET, "/api/v1/bookmarks")
        .session_cookie("s1")
        .send()
        .await;

    assert_eq!(response.status(), StatusCode::SERVICE_UNAVAILABLE);
    assert!(harness.store.has_session("s1").await);
}

#[tokio::test]
async fn refresh_success_updates_session_and_relays_new_bearer() {
    let harness = Harness::new(IdpBehavior::default()).await;
    harness.store.put_session("s1", expired_session()).await;

    let response = harness
        .request(Method::GET, "/api/v1/bookmarks")
        .session_cookie("s1")
        .send()
        .await;

    assert_eq!(response.status(), StatusCode::OK);
    let record = harness.backend.record().await;
    assert_eq!(record.authorization, "Bearer refreshed-access");
    let session = harness.store.load_session("s1").await.unwrap().unwrap();
    assert_eq!(session.refresh_token, "refreshed-refresh");
}

#[tokio::test]
async fn logout_destroys_local_session_and_returns_204() {
    let harness = Harness::new(IdpBehavior::default()).await;
    harness
        .store
        .put_session("s1", future_session("access", "refresh"))
        .await;

    let response = harness
        .request(Method::POST, "/auth/logout")
        .session_cookie("s1")
        .send()
        .await;

    assert_eq!(response.status(), StatusCode::NO_CONTENT);
    assert!(!harness.store.has_session("s1").await);
    assert!(clears_cookie(response.headers(), SESSION_COOKIE));
    assert_eq!(harness.idp.logout_calls().await, 1);
}

struct Harness {
    app: Router,
    store: Arc<MemorySessionStore>,
    backend: BackendServer,
    idp: IdpServer,
}

impl Harness {
    async fn new(idp_behavior: IdpBehavior) -> Self {
        let backend = BackendServer::spawn().await;
        let idp = IdpServer::spawn(idp_behavior).await;
        let config = Arc::new(Config {
            port: "0".to_string(),
            backend_url: backend.url.parse().unwrap(),
            frontend_url: None,
            spa_root: None,
            redis_url: "redis://localhost:6379".to_string(),
            oidc_issuer_uri: format!("{}/realms/stackverse", idp.url),
            oidc_internal_issuer_uri: format!("{}/realms/stackverse", idp.url),
            oidc_client_id: "stackverse-gateway".to_string(),
            oidc_client_secret: "stackverse-secret".to_string(),
            public_url: "http://localhost:8000".parse().unwrap(),
            log_level: "error".to_string(),
            log_format: "text".to_string(),
            otel_disabled: true,
        });
        let http = reqwest::Client::builder()
            .redirect(reqwest::redirect::Policy::none())
            .build()
            .unwrap();
        let store = Arc::new(MemorySessionStore::new());
        let state = AppState {
            config: config.clone(),
            store: store.clone(),
            oidc: Arc::new(OidcClient::new(config, http.clone())),
            http,
        };
        Self {
            app: app(state),
            store,
            backend,
            idp,
        }
    }

    fn request(&self, method: Method, uri: &str) -> RequestBuilder {
        RequestBuilder {
            app: self.app.clone(),
            method,
            uri: uri.to_string(),
            headers: HeaderMap::new(),
            body: Body::empty(),
        }
    }
}

struct RequestBuilder {
    app: Router,
    method: Method,
    uri: String,
    headers: HeaderMap,
    body: Body,
}

impl RequestBuilder {
    fn header<K, V>(mut self, key: K, value: V) -> Self
    where
        K: TryInto<header::HeaderName>,
        K::Error: std::fmt::Debug,
        V: TryInto<header::HeaderValue>,
        V::Error: std::fmt::Debug,
    {
        self.headers
            .insert(key.try_into().unwrap(), value.try_into().unwrap());
        self
    }

    fn session_cookie(self, value: &str) -> Self {
        self.header(header::COOKIE, format!("{SESSION_COOKIE}={value}"))
    }

    fn json_body(mut self, body: &'static str) -> Self {
        self.headers
            .insert(header::CONTENT_TYPE, "application/json".parse().unwrap());
        self.body = Body::from(body);
        self
    }

    async fn send(self) -> Response {
        let mut request = Request::builder()
            .method(self.method)
            .uri(self.uri)
            .body(self.body)
            .unwrap();
        *request.headers_mut() = self.headers;
        self.app.oneshot(request).await.unwrap()
    }
}

#[derive(Clone)]
struct BackendServer {
    url: String,
    state: Arc<Mutex<BackendRecord>>,
    _task: Arc<tokio::task::JoinHandle<()>>,
}

#[derive(Clone, Default)]
struct BackendRecord {
    authorization: String,
    cookie: String,
    csrf: String,
}

impl BackendServer {
    async fn spawn() -> Self {
        let state = Arc::new(Mutex::new(BackendRecord::default()));
        let app = Router::new()
            .fallback(any(backend_handler))
            .with_state(state.clone());
        let server = spawn_router(app).await;
        Self {
            url: server.url,
            state,
            _task: server.task,
        }
    }

    async fn record(&self) -> BackendRecord {
        self.state.lock().await.clone()
    }
}

async fn backend_handler(
    State(state): State<Arc<Mutex<BackendRecord>>>,
    headers: HeaderMap,
) -> Response {
    let mut record = state.lock().await;
    record.authorization = headers
        .get(header::AUTHORIZATION)
        .and_then(|value| value.to_str().ok())
        .unwrap_or_default()
        .to_string();
    record.cookie = headers
        .get(header::COOKIE)
        .and_then(|value| value.to_str().ok())
        .unwrap_or_default()
        .to_string();
    record.csrf = headers
        .get(CSRF_HEADER)
        .and_then(|value| value.to_str().ok())
        .unwrap_or_default()
        .to_string();
    let mut response = Json(json!({"ok": true})).into_response();
    response
        .headers_mut()
        .insert(header::CACHE_CONTROL, "no-cache".parse().unwrap());
    response
        .headers_mut()
        .insert(header::ETAG, "\"bundle-v1\"".parse().unwrap());
    response
}

#[derive(Clone)]
struct IdpServer {
    url: String,
    state: Arc<Mutex<IdpState>>,
    _task: Arc<tokio::task::JoinHandle<()>>,
}

#[derive(Clone)]
struct IdpState {
    behavior: IdpBehavior,
    issuer: String,
    private_key_pem: String,
    public_key: RsaPublicKey,
    logout_calls: usize,
}

#[derive(Clone)]
struct IdpBehavior {
    refresh_status: StatusCode,
}

impl Default for IdpBehavior {
    fn default() -> Self {
        Self {
            refresh_status: StatusCode::OK,
        }
    }
}

impl IdpServer {
    async fn spawn(behavior: IdpBehavior) -> Self {
        let mut rng = OsRng;
        let private_key = RsaPrivateKey::new(&mut rng, 2048).unwrap();
        let public_key = private_key.to_public_key();
        let private_key_pem = private_key
            .to_pkcs8_pem(LineEnding::LF)
            .unwrap()
            .to_string();
        let state = Arc::new(Mutex::new(IdpState {
            behavior,
            issuer: String::new(),
            private_key_pem,
            public_key,
            logout_calls: 0,
        }));
        let app = Router::new()
            .route(
                "/realms/stackverse/protocol/openid-connect/certs",
                any(jwks_handler),
            )
            .route(
                "/realms/stackverse/protocol/openid-connect/token",
                any(token_handler),
            )
            .route(
                "/realms/stackverse/protocol/openid-connect/logout",
                any(logout_handler),
            )
            .with_state(state.clone());
        let server = spawn_router(app).await;
        state.lock().await.issuer = format!("{}/realms/stackverse", server.url);
        Self {
            url: server.url,
            state,
            _task: server.task,
        }
    }

    async fn logout_calls(&self) -> usize {
        self.state.lock().await.logout_calls
    }
}

async fn jwks_handler(State(state): State<Arc<Mutex<IdpState>>>) -> Json<serde_json::Value> {
    let state = state.lock().await;
    Json(json!({
        "keys": [{
            "kty": "RSA",
            "use": "sig",
            "kid": "test-key",
            "alg": "RS256",
            "n": base64_url(state.public_key.n().to_bytes_be()),
            "e": base64_url(state.public_key.e().to_bytes_be())
        }]
    }))
}

async fn token_handler(
    State(state): State<Arc<Mutex<IdpState>>>,
    axum::Form(form): axum::Form<std::collections::HashMap<String, String>>,
) -> Response {
    let state = state.lock().await;
    if form.get("grant_type").map(String::as_str) == Some("refresh_token") {
        if state.behavior.refresh_status != StatusCode::OK {
            return (
                state.behavior.refresh_status,
                Json(json!({"error": "temporary"})),
            )
                .into_response();
        }
        return Json(json!({
            "access_token": "refreshed-access",
            "refresh_token": "refreshed-refresh",
            "expires_in": 300,
            "token_type": "Bearer"
        }))
        .into_response();
    }
    assert_eq!(
        form.get("code_verifier")
            .is_some_and(|value| !value.is_empty()),
        true
    );
    Json(json!({
        "access_token": "access",
        "refresh_token": "refresh",
        "expires_in": 300,
        "id_token": signed_id_token(&state),
        "token_type": "Bearer"
    }))
    .into_response()
}

async fn logout_handler(State(state): State<Arc<Mutex<IdpState>>>) -> StatusCode {
    state.lock().await.logout_calls += 1;
    StatusCode::NO_CONTENT
}

fn signed_id_token(state: &IdpState) -> String {
    let mut header = Header::new(Algorithm::RS256);
    header.kid = Some("test-key".to_string());
    let now = Utc::now();
    let claims = json!({
        "iss": state.issuer,
        "aud": "stackverse-gateway",
        "sub": "subject-demo",
        "preferred_username": "demo",
        "iat": now.timestamp(),
        "exp": (now + TimeDelta::minutes(5)).timestamp()
    });
    encode(
        &header,
        &claims,
        &EncodingKey::from_rsa_pem(state.private_key_pem.as_bytes()).unwrap(),
    )
    .unwrap()
}

struct SpawnedRouter {
    url: String,
    task: Arc<tokio::task::JoinHandle<()>>,
}

async fn spawn_router(app: Router) -> SpawnedRouter {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let task = tokio::spawn(async move {
        let _ = axum::serve(listener, app).await;
    });
    SpawnedRouter {
        url: format!("http://{addr}"),
        task: Arc::new(task),
    }
}

fn future_session(access_token: &str, refresh_token: &str) -> SessionData {
    let now = Utc::now();
    SessionData {
        username: "demo".to_string(),
        access_token: access_token.to_string(),
        refresh_token: refresh_token.to_string(),
        id_token: "id-token".to_string(),
        expires_at: now + TimeDelta::hours(1),
        created_at: now,
        updated_at: now,
    }
}

fn expired_session() -> SessionData {
    let now = Utc::now() - TimeDelta::minutes(1);
    SessionData {
        username: "demo".to_string(),
        access_token: "old-access".to_string(),
        refresh_token: "old-refresh".to_string(),
        id_token: "id-token".to_string(),
        expires_at: now,
        created_at: now - TimeDelta::hours(1),
        updated_at: now,
    }
}

async fn response_body(response: Response) -> String {
    let bytes = to_bytes(response.into_body(), usize::MAX).await.unwrap();
    String::from_utf8(bytes.to_vec()).unwrap()
}

fn set_cookie_value(headers: &HeaderMap, name: &str) -> Option<String> {
    headers
        .get_all(header::SET_COOKIE)
        .iter()
        .find_map(|value| {
            let value = value.to_str().ok()?;
            let (candidate, rest) = value.split_once('=')?;
            if candidate == name {
                Some(rest.split(';').next().unwrap_or_default().to_string())
            } else {
                None
            }
        })
}

fn clears_cookie(headers: &HeaderMap, name: &str) -> bool {
    headers.get_all(header::SET_COOKIE).iter().any(|value| {
        value.to_str().is_ok_and(|value| {
            value.starts_with(&format!("{name}=")) && value.contains("Max-Age=0")
        })
    })
}

fn assert_content_type(response: &Response, expected: &str) {
    assert!(
        response
            .headers()
            .get(header::CONTENT_TYPE)
            .unwrap()
            .to_str()
            .unwrap()
            .starts_with(expected)
    );
}

fn assert_document_headers(response: &Response, expect_hsts: bool) {
    assert_eq!(
        response
            .headers()
            .get(header::X_CONTENT_TYPE_OPTIONS)
            .unwrap(),
        "nosniff"
    );
    assert_eq!(
        response.headers().get(header::REFERRER_POLICY).unwrap(),
        "same-origin"
    );
    assert_eq!(
        response
            .headers()
            .get(header::CONTENT_SECURITY_POLICY)
            .unwrap(),
        CONTENT_SECURITY_POLICY
    );
    assert_eq!(
        response.headers().get(header::X_FRAME_OPTIONS).unwrap(),
        "DENY"
    );
    assert_eq!(
        response
            .headers()
            .get("cross-origin-opener-policy")
            .unwrap(),
        "same-origin"
    );
    assert_eq!(
        response
            .headers()
            .get("cross-origin-resource-policy")
            .unwrap(),
        "same-origin"
    );
    assert_hsts(response, expect_hsts);
}

fn assert_api_headers(response: &Response, expect_hsts: bool) {
    assert_eq!(
        response
            .headers()
            .get(header::X_CONTENT_TYPE_OPTIONS)
            .unwrap(),
        "nosniff"
    );
    assert!(response.headers().get(header::REFERRER_POLICY).is_none());
    assert!(
        response
            .headers()
            .get(header::CONTENT_SECURITY_POLICY)
            .is_none()
    );
    assert!(response.headers().get(header::X_FRAME_OPTIONS).is_none());
    assert!(
        response
            .headers()
            .get("cross-origin-opener-policy")
            .is_none()
    );
    assert!(
        response
            .headers()
            .get("cross-origin-resource-policy")
            .is_none()
    );
    assert_hsts(response, expect_hsts);
}

fn assert_hsts(response: &Response, expected: bool) {
    if expected {
        assert_eq!(
            response
                .headers()
                .get(header::STRICT_TRANSPORT_SECURITY)
                .unwrap(),
            STRICT_TRANSPORT_SECURITY
        );
    } else {
        assert!(
            response
                .headers()
                .get(header::STRICT_TRANSPORT_SECURITY)
                .is_none()
        );
    }
}

fn base64_url(input: Vec<u8>) -> String {
    use base64::Engine;
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(input)
}

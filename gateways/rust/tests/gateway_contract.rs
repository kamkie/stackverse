use std::path::PathBuf;
use std::sync::Arc;

use axum::body::{Body, to_bytes};
use axum::extract::State;
use axum::http::{HeaderMap, Method, Request, StatusCode, Uri, header};
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
use stackverse_gateway_rust::{AppState, app, install_tls_provider};
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
async fn local_spa_fallback_only_handles_get_and_head() {
    let harness = Harness::new(IdpBehavior::default()).await;

    let get = harness
        .request(Method::GET, "/not-a-real-route")
        .send()
        .await;
    assert_eq!(get.status(), StatusCode::OK);
    assert_content_type(&get, "text/html");

    let post = harness
        .request(Method::POST, "/not-a-real-route")
        .send()
        .await;
    assert_eq!(post.status(), StatusCode::NOT_FOUND);
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
        refresh: RefreshBehavior::Status(StatusCode::BAD_REQUEST),
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
        refresh: RefreshBehavior::Status(StatusCode::SERVICE_UNAVAILABLE),
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

#[tokio::test]
async fn health_readiness_and_anonymous_session_endpoints_expose_operational_boundaries() {
    let harness = Harness::new(IdpBehavior::default()).await;

    let health = harness.request(Method::GET, "/healthz").send().await;
    assert_eq!(health.status(), StatusCode::OK);
    let ready = harness.request(Method::GET, "/readyz").send().await;
    assert_eq!(ready.status(), StatusCode::OK);

    let anonymous = harness.request(Method::GET, "/auth/session").send().await;
    assert_eq!(anonymous.status(), StatusCode::OK);
    assert_eq!(response_body(anonymous).await, r#"{"authenticated":false}"#);

    let missing_ticket = harness
        .request(Method::GET, "/auth/session")
        .session_cookie("missing")
        .send()
        .await;
    assert_eq!(
        response_body(missing_ticket).await,
        r#"{"authenticated":false}"#
    );
}

#[tokio::test]
async fn secure_public_url_adds_hsts_and_secure_cookie_attributes_by_cookie_role() {
    let harness = Harness::with_options(
        IdpBehavior::default(),
        HarnessOptions {
            public_url: "https://gateway.example:8443/base".to_string(),
            ..Default::default()
        },
    )
    .await;

    let session = harness.request(Method::GET, "/auth/session").send().await;
    assert_document_headers(&session, true);
    let csrf_cookie = set_cookie(session.headers(), CSRF_COOKIE).expect("CSRF cookie");
    assert!(csrf_cookie.contains("Secure"));
    assert!(csrf_cookie.contains("SameSite=Lax"));
    assert!(!csrf_cookie.contains("HttpOnly"));

    let login = harness.request(Method::GET, "/auth/login").send().await;
    assert_document_headers(&login, true);
    let login_cookie = set_cookie(login.headers(), LOGIN_STATE_COOKIE).expect("login cookie");
    assert!(login_cookie.contains("Secure"));
    assert!(login_cookie.contains("HttpOnly"));
    assert!(login_cookie.contains("Path=/auth/callback"));

    let api = harness
        .request(Method::GET, "/api/v2/bookmarks?visibility=public")
        .send()
        .await;
    assert_api_headers(&api, true);
    assert!(api.headers().get("access-control-allow-origin").is_none());
}

#[tokio::test]
async fn every_contract_mutation_method_requires_double_submit_csrf() {
    let harness = Harness::new(IdpBehavior::default()).await;
    for method in [Method::POST, Method::PUT, Method::PATCH, Method::DELETE] {
        let denied = harness
            .request(method.clone(), "/api/v1/admin/reports/report-1")
            .send()
            .await;
        assert_eq!(denied.status(), StatusCode::FORBIDDEN, "{method}");

        let allowed = harness
            .request(method.clone(), "/api/v1/bookmarks/bookmark-1")
            .header(header::COOKIE, format!("{CSRF_COOKIE}=token"))
            .header(CSRF_HEADER, "token")
            .header("sec-fetch-site", "none")
            .send()
            .await;
        assert_eq!(allowed.status(), StatusCode::OK, "{method}");
    }

    let existing = harness
        .request(Method::GET, "/auth/session")
        .header(header::COOKIE, format!("{CSRF_COOKIE}=existing"))
        .send()
        .await;
    assert!(set_cookie(existing.headers(), CSRF_COOKIE).is_none());
}

#[tokio::test]
async fn backend_authorization_problem_for_moderation_is_relayed_without_gateway_rewrite() {
    let harness = Harness::new(IdpBehavior::default()).await;
    harness
        .store
        .put_session("regular", future_session("regular-access", "refresh"))
        .await;

    let response = harness
        .request(Method::GET, "/api/v1/admin/reports")
        .session_cookie("regular")
        .send()
        .await;

    assert_eq!(response.status(), StatusCode::FORBIDDEN);
    assert_api_headers(&response, false);
    assert_content_type(&response, "application/problem+json");
    assert_eq!(
        response_body(response).await,
        r#"{"type":"about:blank","title":"Forbidden","status":403,"detail":"Moderator role required."}"#
    );
    let record = harness.backend.record().await;
    assert_eq!(record.authorization, "Bearer regular-access");
    assert_eq!(record.uri, "/api/v1/admin/reports");
}

#[tokio::test]
async fn api_proxy_preserves_method_query_body_and_cache_revalidation_response() {
    let harness = Harness::new(IdpBehavior::default()).await;

    let created = harness
        .request(Method::POST, "/api/v1/bookmarks?source=test")
        .header(header::COOKIE, format!("{CSRF_COOKIE}=token"))
        .header(CSRF_HEADER, "token")
        .json_body(r#"{"url":"https://example.com","title":"Example"}"#)
        .send()
        .await;
    assert_eq!(created.status(), StatusCode::OK);
    let record = harness.backend.record().await;
    assert_eq!(record.method, Method::POST);
    assert_eq!(record.uri, "/api/v1/bookmarks?source=test");
    assert_eq!(
        record.body,
        r#"{"url":"https://example.com","title":"Example"}"#
    );
    assert_eq!(record.csrf, "");

    let not_modified = harness
        .request(Method::GET, "/api/v1/messages/bundle?not_modified=true")
        .send()
        .await;
    assert_eq!(not_modified.status(), StatusCode::NOT_MODIFIED);
    assert_eq!(
        not_modified.headers().get(header::ETAG).unwrap(),
        "\"bundle-v1\""
    );
    assert_eq!(
        not_modified.headers().get(header::CACHE_CONTROL).unwrap(),
        "no-cache"
    );
    assert_eq!(
        not_modified
            .headers()
            .get(header::CONTENT_LANGUAGE)
            .unwrap(),
        "pl"
    );
    assert!(response_body(not_modified).await.is_empty());
}

#[tokio::test]
async fn frontend_proxy_strips_browser_cookie_and_preserves_fallback_path() {
    let harness = Harness::with_options(
        IdpBehavior::default(),
        HarnessOptions {
            proxy_frontend: true,
            ..Default::default()
        },
    )
    .await;

    let response = harness
        .request(Method::GET, "/deep/frontend/route?tab=one")
        .header(header::COOKIE, "stackverse_session=browser-only")
        .send()
        .await;
    assert_eq!(response.status(), StatusCode::OK);
    assert_document_headers(&response, false);
    let record = harness.backend.record().await;
    assert_eq!(record.cookie, "");
    assert_eq!(record.uri, "/deep/frontend/route?tab=one");
}

#[tokio::test]
async fn spa_root_serves_assets_and_falls_back_to_index_for_client_routes() {
    let root = std::env::temp_dir().join(format!("stackverse-rust-spa-{}", uuid::Uuid::new_v4()));
    tokio::fs::create_dir_all(&root).await.unwrap();
    tokio::fs::write(root.join("index.html"), b"<main>test shell</main>")
        .await
        .unwrap();
    tokio::fs::write(root.join("app.js"), b"console.error('test')")
        .await
        .unwrap();
    let harness = Harness::with_options(
        IdpBehavior::default(),
        HarnessOptions {
            spa_root: Some(root.clone()),
            ..Default::default()
        },
    )
    .await;

    let asset = harness.request(Method::GET, "/app.js").send().await;
    assert_content_type(&asset, "text/javascript");
    assert_eq!(response_body(asset).await, "console.error('test')");

    let route = harness
        .request(Method::GET, "/bookmarks/client-route")
        .send()
        .await;
    assert_content_type(&route, "text/html");
    assert_eq!(response_body(route).await, "<main>test shell</main>");

    tokio::fs::remove_dir_all(root).await.unwrap();
}

#[tokio::test]
async fn mismatched_callback_state_does_not_consume_the_server_side_login_state() {
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
        .unwrap();

    let callback = harness
        .request(
            Method::GET,
            &format!("/auth/callback?code=ok&state={state}"),
        )
        .header(
            header::COOKIE,
            format!("{LOGIN_STATE_COOKIE}=different-state"),
        )
        .send()
        .await;
    assert_eq!(callback.status(), StatusCode::FOUND);
    assert!(set_cookie_value(callback.headers(), SESSION_COOKIE).is_none());
    assert!(
        harness
            .store
            .consume_oauth_state(&state)
            .await
            .unwrap()
            .is_some()
    );
}

#[tokio::test]
async fn malformed_session_without_access_token_is_destroyed_and_relays_anonymous() {
    let harness = Harness::new(IdpBehavior::default()).await;
    harness
        .store
        .put_session("malformed", future_session("", "refresh"))
        .await;

    let response = harness
        .request(Method::GET, "/api/v1/bookmarks")
        .session_cookie("malformed")
        .send()
        .await;

    assert_eq!(response.status(), StatusCode::OK);
    assert!(!harness.store.has_session("malformed").await);
    assert!(clears_cookie(response.headers(), SESSION_COOKIE));
    assert_eq!(harness.backend.record().await.authorization, "");
}

#[tokio::test]
async fn refresh_rate_limit_and_bad_success_payload_keep_session_and_return_503() {
    for refresh in [
        RefreshBehavior::Status(StatusCode::TOO_MANY_REQUESTS),
        RefreshBehavior::MalformedJson,
        RefreshBehavior::MissingAccessToken,
    ] {
        let harness = Harness::new(IdpBehavior { refresh }).await;
        harness.store.put_session("s1", expired_session()).await;

        let response = harness
            .request(Method::GET, "/api/v1/bookmarks")
            .session_cookie("s1")
            .send()
            .await;
        assert_eq!(response.status(), StatusCode::SERVICE_UNAVAILABLE);
        assert!(harness.store.has_session("s1").await);
        assert_content_type(&response, "application/problem+json");
    }
}

struct Harness {
    app: Router,
    store: Arc<MemorySessionStore>,
    backend: BackendServer,
    idp: IdpServer,
}

#[derive(Default)]
struct HarnessOptions {
    public_url: String,
    spa_root: Option<PathBuf>,
    proxy_frontend: bool,
}

impl Harness {
    async fn new(idp_behavior: IdpBehavior) -> Self {
        Self::with_options(idp_behavior, HarnessOptions::default()).await
    }

    async fn with_options(idp_behavior: IdpBehavior, options: HarnessOptions) -> Self {
        install_tls_provider();
        let backend = BackendServer::spawn().await;
        let idp = IdpServer::spawn(idp_behavior).await;
        let public_url = if options.public_url.is_empty() {
            "http://localhost:8000".to_string()
        } else {
            options.public_url
        };
        let config = Arc::new(Config {
            port: "0".to_string(),
            backend_url: backend.url.parse().unwrap(),
            frontend_url: options.proxy_frontend.then(|| backend.url.parse().unwrap()),
            spa_root: options.spa_root,
            redis_url: "redis://localhost:6379".to_string(),
            oidc_issuer_uri: format!("{}/realms/stackverse", idp.url),
            oidc_internal_issuer_uri: format!("{}/realms/stackverse", idp.url),
            oidc_client_id: "stackverse-gateway".to_string(),
            oidc_client_secret: "stackverse-secret".to_string(),
            public_url: public_url.parse().unwrap(),
            log_level: "error".to_string(),
            log_format: "text".to_string(),
            otel_disabled: true,
        });
        let http = reqwest::Client::builder()
            .redirect(reqwest::redirect::Policy::none())
            .build()
            .unwrap();
        let store = Arc::new(MemorySessionStore::new());
        let oidc = Arc::new(OidcClient::new(config.clone(), http.clone()));
        let state = AppState::new(config, store.clone(), oidc, http);
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

#[derive(Clone)]
struct BackendRecord {
    method: Method,
    uri: String,
    body: String,
    authorization: String,
    cookie: String,
    csrf: String,
}

impl Default for BackendRecord {
    fn default() -> Self {
        Self {
            method: Method::GET,
            uri: String::new(),
            body: String::new(),
            authorization: String::new(),
            cookie: String::new(),
            csrf: String::new(),
        }
    }
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
    method: Method,
    uri: Uri,
    headers: HeaderMap,
    body: Body,
) -> Response {
    let body = String::from_utf8(to_bytes(body, usize::MAX).await.unwrap().to_vec()).unwrap();
    let mut record = state.lock().await;
    record.method = method;
    record.uri = uri.to_string();
    record.body = body;
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
    if uri.path() == "/api/v1/admin/reports" {
        return Response::builder()
            .status(StatusCode::FORBIDDEN)
            .header(header::CONTENT_TYPE, "application/problem+json")
            .body(Body::from(
                r#"{"type":"about:blank","title":"Forbidden","status":403,"detail":"Moderator role required."}"#,
            ))
            .unwrap();
    }
    if uri.query() == Some("not_modified=true") {
        return Response::builder()
            .status(StatusCode::NOT_MODIFIED)
            .header(header::CACHE_CONTROL, "no-cache")
            .header(header::ETAG, "\"bundle-v1\"")
            .header(header::CONTENT_LANGUAGE, "pl")
            .body(Body::empty())
            .unwrap();
    }
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
    refresh: RefreshBehavior,
}

#[derive(Clone)]
enum RefreshBehavior {
    Success,
    Status(StatusCode),
    MalformedJson,
    MissingAccessToken,
}

impl Default for IdpBehavior {
    fn default() -> Self {
        Self {
            refresh: RefreshBehavior::Success,
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
        match &state.behavior.refresh {
            RefreshBehavior::Status(status) => {
                return (*status, Json(json!({"error": "temporary"}))).into_response();
            }
            RefreshBehavior::MalformedJson => {
                return Response::builder()
                    .status(StatusCode::OK)
                    .header(header::CONTENT_TYPE, "application/json")
                    .body(Body::from("not-json"))
                    .unwrap();
            }
            RefreshBehavior::MissingAccessToken => {
                return Json(json!({
                    "refresh_token": "refreshed-refresh",
                    "expires_in": 300,
                    "token_type": "Bearer"
                }))
                .into_response();
            }
            RefreshBehavior::Success => {}
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
    let value = set_cookie(headers, name)?;
    let (_, rest) = value.split_once('=')?;
    Some(rest.split(';').next().unwrap_or_default().to_string())
}

fn set_cookie(headers: &HeaderMap, name: &str) -> Option<String> {
    headers
        .get_all(header::SET_COOKIE)
        .iter()
        .find_map(|value| {
            let value = value.to_str().ok()?;
            let (candidate, _) = value.split_once('=')?;
            if candidate == name {
                Some(value.to_string())
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

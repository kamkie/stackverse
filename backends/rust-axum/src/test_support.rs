use std::sync::Arc;

use axum::Router;
use axum::body::{Body, to_bytes};
use axum::extract::Extension;
use axum::http::{Method, Request, Response, header};
use serde_json::Value;
use sqlx::PgPool;

use crate::AppState;
use crate::auth::{Identity, JwksCache};
use crate::config::Config;

pub(crate) static MIGRATOR: sqlx::migrate::Migrator = sqlx::migrate!("./migrations");

pub(crate) fn state(pool: PgPool) -> AppState {
    crate::install_tls_provider();
    let config = Arc::new(Config {
        port: "8080".to_string(),
        db_host: "localhost".to_string(),
        db_port: "5432".to_string(),
        db_name: "stackverse_test".to_string(),
        db_user: "stackverse".to_string(),
        db_password: "stackverse".to_string(),
        oidc_issuer_uri: "http://localhost:8180/realms/stackverse".to_string(),
        oidc_jwks_uri: Some(
            "http://localhost:8180/realms/stackverse/protocol/openid-connect/certs".to_string(),
        ),
        seed_messages_dir: "../../spec/messages".to_string(),
        log_level: "info".to_string(),
        log_format: "json".to_string(),
        otel_disabled: true,
    });
    let http = reqwest::Client::new();
    AppState {
        config: config.clone(),
        pool,
        jwks: Arc::new(JwksCache::new(config, http.clone())),
        http,
    }
}

pub(crate) fn identity(username: &str, roles: &[&str]) -> Identity {
    Identity {
        username: username.to_string(),
        name: Some(format!("{username} Example")),
        email: Some(format!("{username}@example.com")),
        roles: roles.iter().map(|role| (*role).to_string()).collect(),
    }
}

pub(crate) fn app(router: Router<AppState>, pool: PgPool, caller: Identity) -> Router {
    router.with_state(state(pool)).layer(Extension(caller))
}

pub(crate) fn anonymous_app(router: Router<AppState>, pool: PgPool) -> Router {
    router.with_state(state(pool))
}

pub(crate) fn request(method: Method, uri: &str) -> Request<Body> {
    Request::builder()
        .method(method)
        .uri(uri)
        .body(Body::empty())
        .unwrap()
}

pub(crate) fn json_request(method: Method, uri: &str, body: Value) -> Request<Body> {
    Request::builder()
        .method(method)
        .uri(uri)
        .header(header::CONTENT_TYPE, "application/json")
        .body(Body::from(body.to_string()))
        .unwrap()
}

pub(crate) async fn json_body(response: Response<Body>) -> Value {
    let bytes = to_bytes(response.into_body(), usize::MAX).await.unwrap();
    serde_json::from_slice(&bytes).unwrap()
}

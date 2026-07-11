mod auth;
mod config;
mod db;
mod error;
mod handlers;
mod logging;
#[cfg(test)]
mod test_support;

use std::net::SocketAddr;
use std::sync::Arc;

use anyhow::Context;
use axum::Router;
use sqlx::PgPool;
use tokio::net::TcpListener;
use tower_http::trace::TraceLayer;

use crate::auth::JwksCache;
use crate::config::Config;

#[derive(Clone)]
pub struct AppState {
    pub config: Arc<Config>,
    pub pool: PgPool,
    pub jwks: Arc<JwksCache>,
    pub http: reqwest::Client,
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let config = Arc::new(Config::load());
    install_tls_provider();
    let _logging = logging::init(&config)?;

    let pool = db::connect(&config)
        .await
        .context("connect to PostgreSQL and apply migrations")?;

    db::seed_messages(&pool, &config.seed_messages_dir)
        .await
        .context("seed localized messages")?;

    let http = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(10))
        .build()
        .context("build HTTP client")?;

    let state = AppState {
        config: config.clone(),
        pool,
        jwks: Arc::new(JwksCache::new(config.clone(), http.clone())),
        http,
    };

    tracing::info!(
        event = "application_start",
        outcome = "success",
        port = %config.port,
        db_host = %config.db_host,
        oidc_issuer_uri = %config.oidc_issuer_uri,
        "Rust Axum backend listening"
    );

    let app = app(state);
    let addr: SocketAddr = format!("0.0.0.0:{}", config.port).parse()?;
    let listener = TcpListener::bind(addr).await?;

    axum::serve(listener, app)
        .with_graceful_shutdown(async {
            let _ = tokio::signal::ctrl_c().await;
            tracing::info!(
                event = "application_stop",
                outcome = "success",
                "Rust Axum backend shutting down"
            );
        })
        .await?;

    Ok(())
}

fn app(state: AppState) -> Router {
    handlers::router(state).layer(TraceLayer::new_for_http())
}

fn install_tls_provider() {
    // Tests and embedders may install first; any installed provider is acceptable.
    let _ = rustls::crypto::ring::default_provider().install_default();
}

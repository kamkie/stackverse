use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use anyhow::Context;
use stackverse_gateway_rust::config::Config;
use stackverse_gateway_rust::logging;
use stackverse_gateway_rust::oidc::OidcClient;
use stackverse_gateway_rust::session::RedisSessionStore;
use stackverse_gateway_rust::{AppState, app};
use tokio::net::TcpListener;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let config = Arc::new(Config::load()?);
    let _logging = logging::init(&config)?;

    let http = reqwest::Client::builder()
        .timeout(Duration::from_secs(10))
        .redirect(reqwest::redirect::Policy::none())
        .build()
        .context("build HTTP client")?;

    let store = Arc::new(
        RedisSessionStore::new(&config.redis_url)
            .await
            .context("connect to Redis")?,
    );
    let oidc = Arc::new(OidcClient::new(config.clone(), http.clone()));
    let state = AppState {
        config: config.clone(),
        store,
        oidc,
        http,
    };

    tracing::info!(
        event = "application_start",
        outcome = "success",
        port = %config.port,
        backend_url = %config.backend_url,
        frontend_url = %config.frontend_url.as_ref().map(|url| url.as_str()).unwrap_or(""),
        public_url = %config.public_url,
        redis = %config.redis_endpoint_for_logs(),
        "Rust Axum gateway listening"
    );

    let addr: SocketAddr = format!("0.0.0.0:{}", config.port).parse()?;
    let listener = TcpListener::bind(addr).await?;
    axum::serve(listener, app(state))
        .with_graceful_shutdown(async {
            let _ = tokio::signal::ctrl_c().await;
            tracing::info!(
                event = "application_stop",
                outcome = "success",
                "Rust Axum gateway shutting down"
            );
        })
        .await?;

    Ok(())
}

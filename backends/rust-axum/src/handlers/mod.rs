use axum::Router;
use axum::extract::State;
use axum::http::{HeaderMap, StatusCode, Uri};
use axum::middleware;
use axum::routing::get;

use crate::AppState;
use crate::auth;
use crate::db;
use crate::error;

mod admin;
mod bookmarks;
mod common;
mod identity;
mod messages;
mod reports;
mod wire;

use common::HandlerResult;

pub fn router(state: AppState) -> Router {
    Router::new()
        .route("/healthz", get(healthz))
        .route("/readyz", get(readyz))
        .merge(bookmarks::routes())
        .merge(messages::routes())
        .merge(identity::routes())
        .merge(reports::routes())
        .merge(admin::routes())
        .fallback(fallback)
        .with_state(state.clone())
        .layer(middleware::from_fn_with_state(state, auth::authenticate))
}

async fn healthz() -> StatusCode {
    StatusCode::OK
}

async fn readyz(State(state): State<AppState>) -> StatusCode {
    if db::ready(&state.pool).await {
        StatusCode::OK
    } else {
        StatusCode::SERVICE_UNAVAILABLE
    }
}

async fn fallback(State(state): State<AppState>, headers: HeaderMap, uri: Uri) -> HandlerResult {
    Err(error::not_found(&state, &headers, &uri))
}

#[cfg(test)]
mod tests;

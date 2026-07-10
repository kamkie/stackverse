use axum::Router;
use axum::extract::{Extension, State};
use axum::http::{HeaderMap, StatusCode, Uri};
use axum::routing::get;
use serde::Serialize;

use super::common::{HandlerResult, require_identity};
use crate::AppState;
use crate::auth::Identity;
use crate::error;

pub(super) fn routes() -> Router<AppState> {
    Router::new().route("/api/v1/me", get(me))
}

#[derive(Serialize)]
struct MeResponse {
    username: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    email: Option<String>,
    roles: Vec<String>,
}

async fn me(
    State(state): State<AppState>,
    headers: HeaderMap,
    uri: Uri,
    identity: Option<Extension<Identity>>,
) -> HandlerResult {
    let identity = require_identity(&state, &headers, &uri, identity).await?;
    Ok(error::json(
        StatusCode::OK,
        &MeResponse {
            username: identity.username,
            name: identity.name,
            email: identity.email,
            roles: identity.roles,
        },
    ))
}

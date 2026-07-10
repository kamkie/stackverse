use axum::extract::rejection::JsonRejection;
use axum::extract::{Extension, Json};
use axum::http::{HeaderMap, StatusCode, Uri};
use axum::response::Response;
use serde_json::Value;
use sqlx::{Postgres, Transaction};
use uuid::Uuid;

use crate::AppState;
use crate::auth::Identity;
use crate::db;
use crate::error::{self, FieldViolation};

pub(super) type HandlerResult = Result<Response, error::AppError>;

pub(super) async fn require_identity(
    state: &AppState,
    headers: &HeaderMap,
    uri: &Uri,
    identity: Option<Extension<Identity>>,
) -> Result<Identity, error::AppError> {
    match identity {
        Some(Extension(identity)) => Ok(identity),
        None => Err(error::unauthorized(state, headers, uri)),
    }
}

pub(super) async fn require_role(
    state: &AppState,
    headers: &HeaderMap,
    uri: &Uri,
    identity: Option<Extension<Identity>>,
    role: &str,
) -> Result<Identity, error::AppError> {
    let identity = match identity {
        Some(Extension(identity)) => identity,
        None => return Err(error::unauthorized(state, headers, uri)),
    };
    if identity.has_role(role) {
        Ok(identity)
    } else {
        tracing::info!(
            event = "authz_denied",
            outcome = "denied",
            actor = %identity.username,
            "Denied a request lacking the required role"
        );
        Err(error::forbidden(state, headers, uri))
    }
}

pub(super) async fn json_body<T>(
    _state: &AppState,
    _headers: &HeaderMap,
    _uri: &Uri,
    body: Result<Json<T>, JsonRejection>,
) -> Result<T, error::AppError> {
    match body {
        Ok(Json(value)) => Ok(value),
        Err(_) => Err(error::problem(
            StatusCode::BAD_REQUEST,
            "Bad Request",
            Some("Invalid JSON request body.".to_string()),
        )),
    }
}

pub(super) fn bad_request(
    _state: &AppState,
    _headers: &HeaderMap,
    _uri: &Uri,
    detail: impl Into<String>,
) -> error::AppError {
    error::problem(StatusCode::BAD_REQUEST, "Bad Request", Some(detail.into()))
}

pub(super) fn db_error(
    state: &AppState,
    headers: &HeaderMap,
    uri: &Uri,
    err: sqlx::Error,
) -> error::AppError {
    if db::pg_not_found(&err) {
        error::not_found(state, headers, uri)
    } else {
        tracing::error!(
            event = "dependency_call_failed",
            outcome = "failure",
            dependency = "postgres",
            error_code = %err
                .as_database_error()
                .and_then(|db| db.code())
                .unwrap_or(std::borrow::Cow::Borrowed("query_failed")),
            "Database call failed"
        );
        error::internal_error(state, headers, uri, anyhow::Error::new(err))
    }
}

pub(super) fn db_result<T>(
    state: &AppState,
    headers: &HeaderMap,
    uri: &Uri,
    result: Result<T, sqlx::Error>,
) -> Result<T, error::AppError> {
    result.map_err(|err| db_error(state, headers, uri, err))
}

pub(super) async fn validation_result<T>(
    state: &AppState,
    headers: &HeaderMap,
    uri: &Uri,
    result: Result<T, Vec<FieldViolation>>,
) -> Result<T, error::AppError> {
    match result {
        Ok(value) => Ok(value),
        Err(fields) => Err(error::validation_problem(state, headers, uri, fields).await),
    }
}

pub(super) async fn audit_tx(
    tx: &mut Transaction<'_, Postgres>,
    actor: &str,
    action: &str,
    target_type: &str,
    target_id: &str,
    detail: Option<Value>,
) -> Result<(), sqlx::Error> {
    sqlx::query(
        "insert into audit_entries (id, actor, action, target_type, target_id, detail, created_at) values ($1, $2, $3, $4, $5, $6, $7)",
    )
    .bind(Uuid::new_v4())
    .bind(actor)
    .bind(action)
    .bind(target_type)
    .bind(target_id)
    .bind(detail)
    .bind(error::now_utc())
    .execute(&mut **tx)
    .await?;
    Ok(())
}

use std::collections::BTreeMap;
use std::sync::LazyLock;

use axum::Router;
use axum::extract::rejection::JsonRejection;
use axum::extract::{Extension, Json, Path, State};
use axum::http::{HeaderMap, StatusCode, Uri};
use axum::routing::get;
use chrono::{DateTime, Utc};
use regex::Regex;
use serde::{Deserialize, Serialize};
use serde_json::json;
use sqlx::{AssertSqlSafe, FromRow, PgPool, Row};
use uuid::Uuid;

use super::common::{
    HandlerResult, audit_tx, bad_request, db_error, db_result, json_body, require_role,
    validation_result,
};
use crate::AppState;
use crate::auth::Identity;
use crate::db;
use crate::error::{self, FieldViolation, Validator};

const MESSAGE_COLUMNS: &str = "id, key, language, text, description, created_at, updated_at";

static MESSAGE_KEY_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^[a-z0-9-]+(\.[a-z0-9-]+)*$").unwrap());
static LANGUAGE_RE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^[a-z]{2}$").unwrap());

pub(super) fn routes() -> Router<AppState> {
    Router::new()
        .route("/api/v1/messages", get(list_messages).post(create_message))
        .route("/api/v1/messages/bundle", get(message_bundle))
        .route(
            "/api/v1/messages/{id}",
            get(get_message).put(update_message).delete(delete_message),
        )
}

#[derive(Debug, FromRow, Clone)]
struct Message {
    id: Uuid,
    key: String,
    language: String,
    text: String,
    description: Option<String>,
    created_at: DateTime<Utc>,
    updated_at: DateTime<Utc>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct MessageResponse {
    id: Uuid,
    key: String,
    language: String,
    text: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    description: Option<String>,
    created_at: DateTime<Utc>,
    updated_at: DateTime<Utc>,
}

impl From<Message> for MessageResponse {
    fn from(message: Message) -> Self {
        Self {
            id: message.id,
            key: message.key,
            language: message.language,
            text: message.text,
            description: message.description,
            created_at: message.created_at,
            updated_at: message.updated_at,
        }
    }
}

#[derive(Deserialize)]
pub(super) struct MessageRequest {
    pub(super) key: Option<String>,
    pub(super) language: Option<String>,
    pub(super) text: Option<String>,
    pub(super) description: Option<String>,
}

pub(super) struct ValidMessage {
    pub(super) key: String,
    pub(super) language: String,
    pub(super) text: String,
    pub(super) description: Option<String>,
}

pub(super) fn validate_message(body: MessageRequest) -> Result<ValidMessage, Vec<FieldViolation>> {
    let mut validator = Validator::default();
    let key = body.key.unwrap_or_default().trim().to_string();
    validator.check(
        MESSAGE_KEY_RE.is_match(&key) && key.chars().count() <= 150,
        "key",
        "validation.message.key.invalid",
    );
    let language = body.language.unwrap_or_default().trim().to_string();
    validator.check(
        LANGUAGE_RE.is_match(&language),
        "language",
        "validation.message.language.invalid",
    );
    let text = body.text.unwrap_or_default();
    validator.check(!text.is_empty(), "text", "validation.message.text.required");
    validator.check(
        text.chars().count() <= 2000,
        "text",
        "validation.message.text.too-long",
    );
    validator.check(
        error::rune_len(&body.description) <= 1000,
        "description",
        "validation.message.description.too-long",
    );
    if validator.is_empty() {
        Ok(ValidMessage {
            key,
            language,
            text,
            description: body.description,
        })
    } else {
        Err(validator.into_fields())
    }
}

async fn list_messages(
    State(state): State<AppState>,
    headers: HeaderMap,
    uri: Uri,
) -> HandlerResult {
    let params = error::parse_query(&uri);
    let (page, size) = match error::parse_page(&params) {
        Ok(value) => value,
        Err(detail) => return Err(bad_request(&state, &headers, &uri, detail)),
    };
    let q = error::first(&params, "q");
    if q.chars().count() > 200 {
        return Err(bad_request(
            &state,
            &headers,
            &uri,
            "q must be at most 200 characters",
        ));
    }
    let key = error::first(&params, "key");
    let language = error::first(&params, "language");
    let q_like = if q.is_empty() {
        String::new()
    } else {
        format!("%{}%", error::escape_like(&q.to_ascii_lowercase()))
    };
    let where_sql = "where ($1 = '' or key = $1) and ($2 = '' or language = $2) and ($3 = '' or lower(key) like $3 escape '\\' or lower(text) like $3 escape '\\')";
    let total = db_result(
        &state,
        &headers,
        &uri,
        sqlx::query_scalar::<_, i64>(AssertSqlSafe(format!(
            "select count(*) from messages {where_sql}"
        )))
        .bind(&key)
        .bind(&language)
        .bind(&q_like)
        .fetch_one(&state.pool)
        .await,
    )?;
    let rows = db_result(&state, &headers, &uri, sqlx::query_as::<_, Message>(AssertSqlSafe(format!(
        "select {MESSAGE_COLUMNS} from messages {where_sql} order by key, language limit $4 offset $5"
    )))
    .bind(&key)
    .bind(&language)
    .bind(&q_like)
    .bind(size)
    .bind(page * size)
    .fetch_all(&state.pool)
    .await)?;
    let items = rows
        .into_iter()
        .map(MessageResponse::from)
        .collect::<Vec<_>>();
    let page = error::page_response(items, page, size, total);
    Ok(error::etag_json(&headers, StatusCode::OK, &page, &[]))
}

#[derive(Serialize)]
struct BundleResponse {
    language: String,
    messages: BTreeMap<String, String>,
}

async fn message_bundle(
    State(state): State<AppState>,
    headers: HeaderMap,
    uri: Uri,
) -> HandlerResult {
    let language = error::resolve_language(&state.pool, &headers, &uri).await;
    let rows = db_result(
        &state,
        &headers,
        &uri,
        sqlx::query("select key, language, text from messages where language = any($1)")
            .bind(vec!["en".to_string(), language.clone()])
            .fetch_all(&state.pool)
            .await,
    )?;
    let mut messages = BTreeMap::<String, String>::new();
    for row in rows {
        let key: String = row.try_get("key").unwrap_or_default();
        let lang: String = row.try_get("language").unwrap_or_default();
        let text: String = row.try_get("text").unwrap_or_default();
        if lang == language || !messages.contains_key(&key) {
            messages.insert(key, text);
        }
    }
    Ok(error::etag_json(
        &headers,
        StatusCode::OK,
        &BundleResponse {
            language: language.clone(),
            messages,
        },
        &[("Content-Language", language)],
    ))
}

async fn get_message(
    State(state): State<AppState>,
    headers: HeaderMap,
    uri: Uri,
    Path(id): Path<Uuid>,
) -> HandlerResult {
    let message = db_result(
        &state,
        &headers,
        &uri,
        sqlx::query_as::<_, Message>(AssertSqlSafe(format!(
            "select {MESSAGE_COLUMNS} from messages where id = $1"
        )))
        .bind(id)
        .fetch_one(&state.pool)
        .await,
    )?;
    Ok(error::etag_json(
        &headers,
        StatusCode::OK,
        &MessageResponse::from(message),
        &[],
    ))
}

async fn create_message(
    State(state): State<AppState>,
    headers: HeaderMap,
    uri: Uri,
    identity: Option<Extension<Identity>>,
    body: Result<Json<MessageRequest>, JsonRejection>,
) -> HandlerResult {
    let actor = require_role(&state, &headers, &uri, identity, "admin")
        .await?
        .username;
    let body = json_body(&state, &headers, &uri, body).await?;
    let input = validation_result(&state, &headers, &uri, validate_message(body)).await?;
    if message_conflict(&state.pool, &input.key, &input.language, Uuid::nil()).await {
        return Err(error::conflict(
            &state,
            &headers,
            &uri,
            format!(
                "A message with key '{}' and language '{}' already exists.",
                input.key, input.language
            ),
        ));
    }
    let now = error::now_utc();
    let message = Message {
        id: Uuid::new_v4(),
        key: input.key,
        language: input.language,
        text: input.text,
        description: input.description,
        created_at: now,
        updated_at: now,
    };
    let mut tx = db_result(&state, &headers, &uri, state.pool.begin().await)?;
    let inserted = sqlx::query(AssertSqlSafe(format!(
        "insert into messages ({MESSAGE_COLUMNS}) values ($1, $2, $3, $4, $5, $6, $7)"
    )))
    .bind(message.id)
    .bind(&message.key)
    .bind(&message.language)
    .bind(&message.text)
    .bind(&message.description)
    .bind(message.created_at)
    .bind(message.updated_at)
    .execute(&mut *tx)
    .await;
    if let Err(err) = inserted {
        if db::pg_unique_violation(&err) {
            return Err(error::conflict(
                &state,
                &headers,
                &uri,
                "A message with that key and language already exists.",
            ));
        }
        return Err(db_error(&state, &headers, &uri, err));
    }
    db_result(
        &state,
        &headers,
        &uri,
        audit_tx(
            &mut tx,
            &actor,
            "message.created",
            "message",
            &message.id.to_string(),
            Some(json!({
                "key": message.key,
                "language": message.language,
                "text": message.text,
                "description": message.description
            })),
        )
        .await,
    )?;
    db_result(&state, &headers, &uri, tx.commit().await)?;
    tracing::info!(
        event = "message_created",
        outcome = "success",
        actor = %actor,
        resource_type = "message",
        resource_id = %message.id,
        message_key = %message.key,
        language = %message.language,
        "Message created"
    );
    Ok(error::json_with_headers(
        StatusCode::CREATED,
        &MessageResponse::from(message.clone()),
        &[("Location", format!("/api/v1/messages/{}", message.id))],
    ))
}

async fn update_message(
    State(state): State<AppState>,
    headers: HeaderMap,
    uri: Uri,
    Path(id): Path<Uuid>,
    identity: Option<Extension<Identity>>,
    body: Result<Json<MessageRequest>, JsonRejection>,
) -> HandlerResult {
    let actor = require_role(&state, &headers, &uri, identity, "admin")
        .await?
        .username;
    let existing = db_result(
        &state,
        &headers,
        &uri,
        sqlx::query_as::<_, Message>(AssertSqlSafe(format!(
            "select {MESSAGE_COLUMNS} from messages where id = $1"
        )))
        .bind(id)
        .fetch_one(&state.pool)
        .await,
    )?;
    let body = json_body(&state, &headers, &uri, body).await?;
    let input = validation_result(&state, &headers, &uri, validate_message(body)).await?;
    if message_conflict(&state.pool, &input.key, &input.language, id).await {
        return Err(error::conflict(
            &state,
            &headers,
            &uri,
            format!(
                "A message with key '{}' and language '{}' already exists.",
                input.key, input.language
            ),
        ));
    }
    let message = Message {
        id,
        key: input.key,
        language: input.language,
        text: input.text,
        description: input.description,
        created_at: existing.created_at,
        updated_at: error::now_utc(),
    };
    let mut tx = db_result(&state, &headers, &uri, state.pool.begin().await)?;
    if let Err(err) = sqlx::query(
        "update messages set key = $2, language = $3, text = $4, description = $5, updated_at = $6 where id = $1",
    )
    .bind(message.id)
    .bind(&message.key)
    .bind(&message.language)
    .bind(&message.text)
    .bind(&message.description)
    .bind(message.updated_at)
    .execute(&mut *tx)
    .await
    {
        if db::pg_unique_violation(&err) {
            return Err(error::conflict(
                &state,
                &headers,
                &uri,
                "A message with that key and language already exists.",
            ));
        }
        return Err(db_error(&state, &headers, &uri, err));
    }
    db_result(
        &state,
        &headers,
        &uri,
        audit_tx(
            &mut tx,
            &actor,
            "message.updated",
            "message",
            &message.id.to_string(),
            Some(json!({
                "key": message.key,
                "language": message.language,
                "text": message.text,
                "description": message.description
            })),
        )
        .await,
    )?;
    db_result(&state, &headers, &uri, tx.commit().await)?;
    tracing::info!(
        event = "message_updated",
        outcome = "success",
        actor = %actor,
        resource_type = "message",
        resource_id = %message.id,
        message_key = %message.key,
        language = %message.language,
        "Message updated"
    );
    Ok(error::json(StatusCode::OK, &MessageResponse::from(message)))
}

async fn delete_message(
    State(state): State<AppState>,
    headers: HeaderMap,
    uri: Uri,
    Path(id): Path<Uuid>,
    identity: Option<Extension<Identity>>,
) -> HandlerResult {
    let actor = require_role(&state, &headers, &uri, identity, "admin")
        .await?
        .username;
    let message = db_result(
        &state,
        &headers,
        &uri,
        sqlx::query_as::<_, Message>(AssertSqlSafe(format!(
            "select {MESSAGE_COLUMNS} from messages where id = $1"
        )))
        .bind(id)
        .fetch_one(&state.pool)
        .await,
    )?;
    let mut tx = db_result(&state, &headers, &uri, state.pool.begin().await)?;
    db_result(
        &state,
        &headers,
        &uri,
        sqlx::query("delete from messages where id = $1")
            .bind(id)
            .execute(&mut *tx)
            .await,
    )?;
    db_result(
        &state,
        &headers,
        &uri,
        audit_tx(
            &mut tx,
            &actor,
            "message.deleted",
            "message",
            &id.to_string(),
            Some(json!({
                "key": message.key,
                "language": message.language,
                "text": message.text,
                "description": message.description
            })),
        )
        .await,
    )?;
    db_result(&state, &headers, &uri, tx.commit().await)?;
    tracing::info!(
        event = "message_deleted",
        outcome = "success",
        actor = %actor,
        resource_type = "message",
        resource_id = %id,
        "Message deleted"
    );
    Ok(error::empty(StatusCode::NO_CONTENT))
}

async fn message_conflict(pool: &PgPool, key: &str, language: &str, excluding: Uuid) -> bool {
    sqlx::query_scalar::<_, bool>(
        "select exists (select 1 from messages where key = $1 and language = $2 and id <> $3)",
    )
    .bind(key)
    .bind(language)
    .bind(excluding)
    .fetch_one(pool)
    .await
    .unwrap_or(false)
}

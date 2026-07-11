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

#[cfg(test)]
mod tests {
    use axum::Router;
    use axum::body::Body;
    use axum::extract::Extension;
    use axum::http::{Method, Request, StatusCode, header};
    use chrono::{TimeZone, Utc};
    use serde_json::{Value, json};
    use sqlx::PgPool;
    use tower::ServiceExt;
    use uuid::Uuid;

    use super::{Message, MessageRequest, MessageResponse, routes, validate_message};
    use crate::auth::Identity;
    use crate::test_support::{MIGRATOR, identity, json_body, json_request, request, state};

    fn app(pool: PgPool, caller: Identity) -> Router {
        routes().with_state(state(pool)).layer(Extension(caller))
    }

    async fn create(app: &Router, key: &str, language: &str, text: &str) -> Value {
        let response = app
            .clone()
            .oneshot(json_request(
                Method::POST,
                "/api/v1/messages",
                json!({
                    "key": key,
                    "language": language,
                    "text": text,
                    "description": "Shown on the home page"
                }),
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::CREATED);
        assert!(response.headers().contains_key(header::LOCATION));
        json_body(response).await
    }

    #[test]
    fn validate_message_trims_fields_and_collects_contract_errors() {
        let message = validate_message(MessageRequest {
            key: Some(" ui.nav.title ".to_string()),
            language: Some(" en ".to_string()),
            text: Some("Welcome".to_string()),
            description: Some("Navigation title".to_string()),
        })
        .unwrap();
        assert_eq!(message.key, "ui.nav.title");
        assert_eq!(message.language, "en");
        assert_eq!(message.text, "Welcome");

        let errors = match validate_message(MessageRequest {
            key: Some("Bad Key".to_string()),
            language: Some("eng".to_string()),
            text: Some(String::new()),
            description: Some("x".repeat(1001)),
        }) {
            Ok(_) => panic!("invalid message should be rejected"),
            Err(errors) => errors,
        };
        let keys = errors
            .into_iter()
            .map(|field| field.message_key)
            .collect::<Vec<_>>();
        assert!(keys.contains(&"validation.message.key.invalid"));
        assert!(keys.contains(&"validation.message.language.invalid"));
        assert!(keys.contains(&"validation.message.text.required"));
        assert!(keys.contains(&"validation.message.description.too-long"));
    }

    #[test]
    fn message_response_omits_missing_description() {
        let now = Utc.with_ymd_and_hms(2026, 7, 5, 12, 0, 0).unwrap();
        let response = MessageResponse::from(Message {
            id: Uuid::nil(),
            key: "ui.title".to_string(),
            language: "en".to_string(),
            text: "Title".to_string(),
            description: None,
            created_at: now,
            updated_at: now,
        });
        let value = serde_json::to_value(response).unwrap();
        assert!(value.get("description").is_none());
        assert_eq!(value["createdAt"], "2026-07-05T12:00:00Z");
    }

    #[sqlx::test(migrator = "MIGRATOR")]
    async fn message_http_flow_covers_i18n_etags_conflicts_and_auditing(pool: PgPool) {
        let admin = app(pool.clone(), identity("admin", &["admin", "moderator"]));
        let english = create(&admin, "ui.greeting", "en", "Hello").await;
        let polish = create(&admin, "ui.greeting", "pl", "Cześć").await;
        let english_id = english["id"].as_str().unwrap();
        let polish_id = polish["id"].as_str().unwrap();

        let duplicate = admin
            .clone()
            .oneshot(json_request(
                Method::POST,
                "/api/v1/messages",
                json!({"key":"ui.greeting","language":"en","text":"Duplicate"}),
            ))
            .await
            .unwrap();
        assert_eq!(duplicate.status(), StatusCode::CONFLICT);

        let list = admin
            .clone()
            .oneshot(request(Method::GET, "/api/v1/messages?q=GREET&size=10"))
            .await
            .unwrap();
        assert_eq!(list.status(), StatusCode::OK);
        let etag = list.headers()[header::ETAG].to_str().unwrap().to_string();
        assert_eq!(json_body(list).await["totalItems"], 2);

        let not_modified = admin
            .clone()
            .oneshot(
                Request::builder()
                    .uri("/api/v1/messages?q=GREET&size=10")
                    .header(header::IF_NONE_MATCH, &etag)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(not_modified.status(), StatusCode::NOT_MODIFIED);

        let bundle = admin
            .clone()
            .oneshot(request(Method::GET, "/api/v1/messages/bundle?lang=pl"))
            .await
            .unwrap();
        assert_eq!(bundle.headers()[header::CONTENT_LANGUAGE], "pl");
        assert_eq!(json_body(bundle).await["messages"]["ui.greeting"], "Cześć");

        let negotiated = admin
            .clone()
            .oneshot(
                Request::builder()
                    .uri("/api/v1/messages/bundle")
                    .header(header::ACCEPT_LANGUAGE, "fr;q=1, pl;q=0.9")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(negotiated.headers()[header::CONTENT_LANGUAGE], "pl");

        let get = admin
            .clone()
            .oneshot(request(
                Method::GET,
                &format!("/api/v1/messages/{english_id}"),
            ))
            .await
            .unwrap();
        assert_eq!(get.status(), StatusCode::OK);
        assert!(get.headers().contains_key(header::ETAG));

        let updated = admin
            .clone()
            .oneshot(json_request(
                Method::PUT,
                &format!("/api/v1/messages/{english_id}"),
                json!({"key":"ui.greeting","language":"en","text":"Welcome"}),
            ))
            .await
            .unwrap();
        assert_eq!(updated.status(), StatusCode::OK);
        assert_eq!(json_body(updated).await["text"], "Welcome");

        let after_write = admin
            .clone()
            .oneshot(request(Method::GET, "/api/v1/messages?q=GREET&size=10"))
            .await
            .unwrap();
        assert_ne!(after_write.headers()[header::ETAG], etag);

        let deleted = admin
            .clone()
            .oneshot(request(
                Method::DELETE,
                &format!("/api/v1/messages/{polish_id}"),
            ))
            .await
            .unwrap();
        assert_eq!(deleted.status(), StatusCode::NO_CONTENT);

        let actions = sqlx::query_scalar::<_, String>(
            "select action from audit_entries order by created_at, action",
        )
        .fetch_all(&pool)
        .await
        .unwrap();
        assert!(actions.contains(&"message.created".to_string()));
        assert!(actions.contains(&"message.updated".to_string()));
        assert!(actions.contains(&"message.deleted".to_string()));
    }

    #[sqlx::test(migrator = "MIGRATOR")]
    async fn message_http_boundaries_enforce_roles_validation_and_not_found(pool: PgPool) {
        let anonymous = routes().with_state(state(pool.clone()));
        let missing_identity = anonymous
            .oneshot(request(Method::POST, "/api/v1/messages"))
            .await
            .unwrap();
        assert_eq!(missing_identity.status(), StatusCode::UNAUTHORIZED);

        let regular = app(pool.clone(), identity("alice", &[]));
        let forbidden = regular
            .oneshot(request(Method::POST, "/api/v1/messages"))
            .await
            .unwrap();
        assert_eq!(forbidden.status(), StatusCode::FORBIDDEN);

        let admin = app(pool, identity("admin", &["admin"]));
        let invalid = admin
            .clone()
            .oneshot(json_request(
                Method::POST,
                "/api/v1/messages",
                json!({"key":"Bad Key","language":"eng","text":""}),
            ))
            .await
            .unwrap();
        assert_eq!(invalid.status(), StatusCode::BAD_REQUEST);

        let invalid_page = admin
            .clone()
            .oneshot(request(Method::GET, "/api/v1/messages?size=0"))
            .await
            .unwrap();
        assert_eq!(invalid_page.status(), StatusCode::BAD_REQUEST);

        let missing = admin
            .oneshot(request(
                Method::GET,
                "/api/v1/messages/00000000-0000-4000-8000-000000000099",
            ))
            .await
            .unwrap();
        assert_eq!(missing.status(), StatusCode::NOT_FOUND);
    }
}

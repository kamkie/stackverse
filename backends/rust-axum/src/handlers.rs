use std::collections::BTreeMap;
use std::sync::LazyLock;

use axum::Router;
use axum::extract::rejection::JsonRejection;
use axum::extract::{Extension, Json, Path, State};
use axum::http::{HeaderMap, StatusCode, Uri};
use axum::middleware;
use axum::response::Response;
use axum::routing::{get, post, put};
use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use chrono::{DateTime, Days, SecondsFormat, TimeZone, Utc};
use regex::Regex;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sqlx::{AssertSqlSafe, FromRow, PgPool, Postgres, QueryBuilder, Row, Transaction};
use url::Url;
use uuid::Uuid;

use crate::AppState;
use crate::auth::{self, Identity};
use crate::db;
use crate::error::{self, FieldViolation, Validator};

const BOOKMARK_COLUMNS: &str =
    "id, owner, url, title, notes, tags, visibility, status, created_at, updated_at";
const MESSAGE_COLUMNS: &str = "id, key, language, text, description, created_at, updated_at";
const REPORT_COLUMNS: &str = "id, bookmark_id, reporter, reason, comment, status, resolved_by, resolved_at, resolution_note, created_at";

type HandlerResult = Result<Response, error::AppError>;

static TAG_RE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^[a-z0-9-]{1,30}$").unwrap());
static MESSAGE_KEY_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^[a-z0-9-]+(\.[a-z0-9-]+)*$").unwrap());
static LANGUAGE_RE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^[a-z]{2}$").unwrap());

pub fn router(state: AppState) -> Router {
    Router::new()
        .route("/healthz", get(healthz))
        .route("/readyz", get(readyz))
        .route(
            "/api/v1/bookmarks",
            get(list_bookmarks_v1).post(create_bookmark),
        )
        .route("/api/v2/bookmarks", get(list_bookmarks_v2))
        .route(
            "/api/v1/bookmarks/{id}",
            get(get_bookmark)
                .put(update_bookmark)
                .delete(delete_bookmark),
        )
        .route("/api/v1/bookmarks/{id}/reports", post(report_bookmark))
        .route("/api/v1/reports", get(list_my_reports))
        .route(
            "/api/v1/reports/{id}",
            put(update_my_report).delete(withdraw_report),
        )
        .route("/api/v1/admin/reports", get(list_report_queue))
        .route("/api/v1/admin/reports/{id}", put(resolve_report))
        .route(
            "/api/v1/admin/bookmarks/{id}/status",
            put(set_bookmark_status),
        )
        .route("/api/v1/admin/users", get(list_users))
        .route("/api/v1/admin/users/{username}", get(get_user))
        .route(
            "/api/v1/admin/users/{username}/status",
            put(set_user_status),
        )
        .route("/api/v1/admin/audit-log", get(list_audit_log))
        .route("/api/v1/admin/stats", get(get_stats))
        .route("/api/v1/messages", get(list_messages).post(create_message))
        .route("/api/v1/messages/bundle", get(message_bundle))
        .route(
            "/api/v1/messages/{id}",
            get(get_message).put(update_message).delete(delete_message),
        )
        .route("/api/v1/tags", get(list_tags))
        .route("/api/v1/me", get(me))
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

async fn require_identity(
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

async fn require_role(
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

async fn json_body<T>(
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

fn bad_request(
    _state: &AppState,
    _headers: &HeaderMap,
    _uri: &Uri,
    detail: impl Into<String>,
) -> error::AppError {
    error::problem(StatusCode::BAD_REQUEST, "Bad Request", Some(detail.into()))
}

fn db_error(state: &AppState, headers: &HeaderMap, uri: &Uri, err: sqlx::Error) -> error::AppError {
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

fn db_result<T>(
    state: &AppState,
    headers: &HeaderMap,
    uri: &Uri,
    result: Result<T, sqlx::Error>,
) -> Result<T, error::AppError> {
    result.map_err(|err| db_error(state, headers, uri, err))
}

async fn validation_result<T>(
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

#[derive(Debug, FromRow, Clone)]
struct Bookmark {
    id: Uuid,
    owner: String,
    url: String,
    title: String,
    notes: Option<String>,
    tags: Vec<String>,
    visibility: String,
    status: String,
    created_at: DateTime<Utc>,
    updated_at: DateTime<Utc>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct BookmarkResponse {
    id: Uuid,
    url: String,
    title: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    notes: Option<String>,
    tags: Vec<String>,
    visibility: String,
    status: String,
    owner: String,
    created_at: DateTime<Utc>,
    updated_at: DateTime<Utc>,
}

impl From<Bookmark> for BookmarkResponse {
    fn from(bookmark: Bookmark) -> Self {
        let mut tags = bookmark.tags;
        tags.sort();
        Self {
            id: bookmark.id,
            url: bookmark.url,
            title: bookmark.title,
            notes: bookmark.notes,
            tags,
            visibility: bookmark.visibility,
            status: bookmark.status,
            owner: bookmark.owner,
            created_at: bookmark.created_at,
            updated_at: bookmark.updated_at,
        }
    }
}

#[derive(Deserialize)]
struct BookmarkRequest {
    url: Option<String>,
    title: Option<String>,
    notes: Option<String>,
    #[serde(default)]
    tags: Vec<String>,
    visibility: Option<String>,
}

struct ValidBookmark {
    url: String,
    title: String,
    notes: Option<String>,
    tags: Vec<String>,
    visibility: String,
}

fn validate_bookmark(body: BookmarkRequest) -> Result<ValidBookmark, Vec<FieldViolation>> {
    let mut validator = Validator::default();
    let url = body.url.unwrap_or_default().trim().to_string();
    if url.is_empty() {
        validator.reject("url", "validation.url.required");
    } else {
        validator.check(
            url.chars().count() <= 2000 && is_http_url(&url),
            "url",
            "validation.url.invalid",
        );
    }

    let title = body.title.unwrap_or_default().trim().to_string();
    validator.check(!title.is_empty(), "title", "validation.title.required");
    validator.check(
        title.chars().count() <= 200,
        "title",
        "validation.title.too-long",
    );
    validator.check(
        error::rune_len(&body.notes) <= 4000,
        "notes",
        "validation.notes.too-long",
    );

    let mut tags = Vec::<String>::new();
    for tag in body.tags {
        let normalized = tag.trim().to_ascii_lowercase();
        if !tags.iter().any(|existing| existing == &normalized) {
            tags.push(normalized);
        }
    }
    validator.check(tags.len() <= 10, "tags", "validation.tags.too-many");
    if tags.iter().any(|tag| !TAG_RE.is_match(tag)) {
        validator.reject("tags", "validation.tag.invalid");
    }

    let visibility = body.visibility.unwrap_or_else(|| "private".to_string());
    if visibility != "private" && visibility != "public" {
        validator.reject("visibility", "validation.visibility.invalid");
    }

    if validator.is_empty() {
        Ok(ValidBookmark {
            url,
            title,
            notes: body.notes,
            tags,
            visibility,
        })
    } else {
        Err(validator.into_fields())
    }
}

fn is_http_url(raw: &str) -> bool {
    Url::parse(raw)
        .ok()
        .is_some_and(|url| matches!(url.scheme(), "http" | "https") && url.host_str().is_some())
}

struct BookmarkListQuery {
    caller: Option<String>,
    visibility: String,
    tags: Vec<String>,
    q: String,
}

async fn parse_bookmark_list_query(
    state: &AppState,
    headers: &HeaderMap,
    uri: &Uri,
    identity: Option<Extension<Identity>>,
) -> Result<BookmarkListQuery, error::AppError> {
    let params = error::parse_query(uri);
    let q = error::first(&params, "q");
    if q.chars().count() > 200 {
        return Err(bad_request(
            state,
            headers,
            uri,
            "q must be at most 200 characters",
        ));
    }
    let visibility = error::first(&params, "visibility");
    if !visibility.is_empty() && visibility != "private" && visibility != "public" {
        return Err(bad_request(
            state,
            headers,
            uri,
            "visibility must be one of: private, public",
        ));
    }
    let mut tags = Vec::new();
    for raw in params.get("tag").into_iter().flatten() {
        let tag = raw.trim().to_ascii_lowercase();
        if !TAG_RE.is_match(&tag) {
            return Err(error::validation_problem(
                state,
                headers,
                uri,
                vec![FieldViolation {
                    field: "tag",
                    message_key: "validation.tag.invalid",
                }],
            )
            .await);
        }
        tags.push(tag);
    }
    let caller = if visibility == "public" {
        None
    } else {
        Some(
            require_identity(state, headers, uri, identity)
                .await?
                .username,
        )
    };
    Ok(BookmarkListQuery {
        caller,
        visibility,
        tags,
        q,
    })
}

fn push_bookmark_where(builder: &mut QueryBuilder<Postgres>, query: &BookmarkListQuery) {
    builder.push(" where ");
    if query.visibility == "public" {
        builder.push("visibility = 'public' and status = 'active'");
    } else {
        builder.push("owner = ");
        builder.push_bind(query.caller.as_deref().unwrap_or_default());
        if !query.visibility.is_empty() {
            builder.push(" and visibility = ");
            builder.push_bind(&query.visibility);
        }
    }
    if !query.tags.is_empty() {
        builder.push(" and ");
        builder.push("tags @> ");
        builder.push_bind(query.tags.clone());
    }
    if !query.q.is_empty() {
        builder.push(" and ");
        let pattern = format!("%{}%", error::escape_like(&query.q.to_ascii_lowercase()));
        builder.push("(lower(title) like ");
        builder.push_bind(pattern.clone());
        builder.push(" escape '\\' or lower(coalesce(notes, '')) like ");
        builder.push_bind(pattern);
        builder.push(" escape '\\')");
    }
}

async fn list_bookmarks_v1(
    State(state): State<AppState>,
    headers: HeaderMap,
    uri: Uri,
    identity: Option<Extension<Identity>>,
) -> HandlerResult {
    let params = error::parse_query(&uri);
    let (page, size) = match error::parse_page(&params) {
        Ok(value) => value,
        Err(detail) => return Err(bad_request(&state, &headers, &uri, detail)),
    };
    let query = parse_bookmark_list_query(&state, &headers, &uri, identity).await?;
    let mut count = QueryBuilder::<Postgres>::new("select count(*) from bookmarks");
    push_bookmark_where(&mut count, &query);
    let total = db_result(
        &state,
        &headers,
        &uri,
        count.build().fetch_one(&state.pool).await,
    )?
    .try_get::<i64, _>(0)
    .unwrap_or(0);
    let mut list =
        QueryBuilder::<Postgres>::new(format!("select {BOOKMARK_COLUMNS} from bookmarks"));
    push_bookmark_where(&mut list, &query);
    list.push(" order by created_at desc, id desc limit ");
    list.push_bind(size);
    list.push(" offset ");
    list.push_bind(page * size);
    let rows = db_result(
        &state,
        &headers,
        &uri,
        list.build_query_as::<Bookmark>()
            .fetch_all(&state.pool)
            .await,
    )?;
    let items = rows
        .into_iter()
        .map(BookmarkResponse::from)
        .collect::<Vec<_>>();
    let page = error::page_response(items, page, size, total);
    Ok(error::json_with_headers(
        StatusCode::OK,
        &page,
        &[
            ("Deprecation", "@1782864000".to_string()),
            ("Sunset", "Thu, 01 Jul 2027 00:00:00 GMT".to_string()),
            (
                "Link",
                "</api/v2/bookmarks>; rel=\"successor-version\"".to_string(),
            ),
        ],
    ))
}

#[derive(Debug)]
struct Cursor {
    created_at: DateTime<Utc>,
    id: Uuid,
}

fn encode_cursor(cursor: Cursor) -> String {
    let raw = format!(
        "{}|{}",
        cursor
            .created_at
            .to_rfc3339_opts(SecondsFormat::AutoSi, true),
        cursor.id
    );
    URL_SAFE_NO_PAD.encode(raw)
}

fn decode_cursor(raw: &str) -> Option<Cursor> {
    let decoded = URL_SAFE_NO_PAD.decode(raw).ok()?;
    let decoded = String::from_utf8(decoded).ok()?;
    let (created_at, id) = decoded.split_once('|')?;
    let created_at = DateTime::parse_from_rfc3339(created_at)
        .ok()?
        .with_timezone(&Utc);
    let id = Uuid::parse_str(id).ok()?;
    Some(Cursor { created_at, id })
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct CursorPage {
    items: Vec<BookmarkResponse>,
    #[serde(skip_serializing_if = "Option::is_none")]
    next_cursor: Option<String>,
}

async fn list_bookmarks_v2(
    State(state): State<AppState>,
    headers: HeaderMap,
    uri: Uri,
    identity: Option<Extension<Identity>>,
) -> HandlerResult {
    let params = error::parse_query(&uri);
    let size = match error::parse_size(&params) {
        Ok(value) => value,
        Err(detail) => return Err(bad_request(&state, &headers, &uri, detail)),
    };
    let cursor = match error::first(&params, "cursor") {
        raw if raw.is_empty() => None,
        raw => match decode_cursor(&raw) {
            Some(cursor) => Some(cursor),
            None => {
                return Err(bad_request(
                    &state,
                    &headers,
                    &uri,
                    "The cursor is malformed or unresolvable.",
                ));
            }
        },
    };
    let query = parse_bookmark_list_query(&state, &headers, &uri, identity).await?;
    let mut list =
        QueryBuilder::<Postgres>::new(format!("select {BOOKMARK_COLUMNS} from bookmarks"));
    push_bookmark_where(&mut list, &query);
    if let Some(cursor) = &cursor {
        list.push(" and (created_at, id) < (");
        list.push_bind(cursor.created_at);
        list.push(", ");
        list.push_bind(cursor.id);
        list.push(")");
    }
    list.push(" order by created_at desc, id desc limit ");
    list.push_bind(size + 1);
    let mut rows = db_result(
        &state,
        &headers,
        &uri,
        list.build_query_as::<Bookmark>()
            .fetch_all(&state.pool)
            .await,
    )?;
    let next_cursor = if rows.len() as i64 > size {
        rows.truncate(size as usize);
        rows.last().map(|last| {
            encode_cursor(Cursor {
                created_at: last.created_at,
                id: last.id,
            })
        })
    } else {
        None
    };
    let items = rows
        .into_iter()
        .map(BookmarkResponse::from)
        .collect::<Vec<_>>();
    Ok(error::json(
        StatusCode::OK,
        &CursorPage { items, next_cursor },
    ))
}

async fn create_bookmark(
    State(state): State<AppState>,
    headers: HeaderMap,
    uri: Uri,
    identity: Option<Extension<Identity>>,
    body: Result<Json<BookmarkRequest>, JsonRejection>,
) -> HandlerResult {
    let identity = require_identity(&state, &headers, &uri, identity).await?;
    let body = json_body(&state, &headers, &uri, body).await?;
    let input = validation_result(&state, &headers, &uri, validate_bookmark(body)).await?;
    let now = error::now_utc();
    let bookmark = Bookmark {
        id: Uuid::new_v4(),
        owner: identity.username,
        url: input.url,
        title: input.title,
        notes: input.notes,
        tags: input.tags,
        visibility: input.visibility,
        status: "active".to_string(),
        created_at: now,
        updated_at: now,
    };
    db_result(&state, &headers, &uri, sqlx::query(AssertSqlSafe(format!(
        "insert into bookmarks ({BOOKMARK_COLUMNS}) values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)"
    )))
    .bind(bookmark.id)
    .bind(&bookmark.owner)
    .bind(&bookmark.url)
    .bind(&bookmark.title)
    .bind(&bookmark.notes)
    .bind(&bookmark.tags)
    .bind(&bookmark.visibility)
    .bind(&bookmark.status)
    .bind(bookmark.created_at)
    .bind(bookmark.updated_at)
    .execute(&state.pool)
    .await)?;
    Ok(error::json_with_headers(
        StatusCode::CREATED,
        &BookmarkResponse::from(bookmark.clone()),
        &[("Location", format!("/api/v1/bookmarks/{}", bookmark.id))],
    ))
}

async fn get_bookmark(
    State(state): State<AppState>,
    headers: HeaderMap,
    uri: Uri,
    Path(id): Path<Uuid>,
    identity: Option<Extension<Identity>>,
) -> HandlerResult {
    let bookmark = db_result(
        &state,
        &headers,
        &uri,
        bookmark_by_id(&state.pool, id).await,
    )?;
    let caller = identity
        .map(|Extension(identity)| identity.username)
        .unwrap_or_default();
    if !bookmark_visible_to(&bookmark, &caller) {
        return Err(error::not_found(&state, &headers, &uri));
    }
    Ok(error::json(
        StatusCode::OK,
        &BookmarkResponse::from(bookmark),
    ))
}

async fn update_bookmark(
    State(state): State<AppState>,
    headers: HeaderMap,
    uri: Uri,
    Path(id): Path<Uuid>,
    identity: Option<Extension<Identity>>,
    body: Result<Json<BookmarkRequest>, JsonRejection>,
) -> HandlerResult {
    let identity = require_identity(&state, &headers, &uri, identity).await?;
    let body = json_body(&state, &headers, &uri, body).await?;
    let input = validation_result(&state, &headers, &uri, validate_bookmark(body)).await?;
    let mut tx = db_result(&state, &headers, &uri, state.pool.begin().await)?;
    let mut bookmark = db_result(
        &state,
        &headers,
        &uri,
        bookmark_by_id_tx(&mut tx, id, true).await,
    )?;
    if bookmark.owner != identity.username {
        return Err(error::not_found(&state, &headers, &uri));
    }
    if bookmark.status == "hidden" && input.visibility == "public" {
        return Err(error::conflict(
            &state,
            &headers,
            &uri,
            "A hidden bookmark cannot be made public.",
        ));
    }
    bookmark.url = input.url;
    bookmark.title = input.title;
    bookmark.notes = input.notes;
    bookmark.tags = input.tags;
    bookmark.visibility = input.visibility;
    bookmark.updated_at = error::now_utc();
    db_result(&state, &headers, &uri, sqlx::query(
        "update bookmarks set url = $2, title = $3, notes = $4, tags = $5, visibility = $6, updated_at = $7 where id = $1",
    )
    .bind(bookmark.id)
    .bind(&bookmark.url)
    .bind(&bookmark.title)
    .bind(&bookmark.notes)
    .bind(&bookmark.tags)
    .bind(&bookmark.visibility)
    .bind(bookmark.updated_at)
    .execute(&mut *tx)
    .await)?;
    db_result(&state, &headers, &uri, tx.commit().await)?;
    Ok(error::json(
        StatusCode::OK,
        &BookmarkResponse::from(bookmark),
    ))
}

async fn delete_bookmark(
    State(state): State<AppState>,
    headers: HeaderMap,
    uri: Uri,
    Path(id): Path<Uuid>,
    identity: Option<Extension<Identity>>,
) -> HandlerResult {
    let identity = require_identity(&state, &headers, &uri, identity).await?;
    let bookmark = db_result(
        &state,
        &headers,
        &uri,
        bookmark_by_id(&state.pool, id).await,
    )?;
    if bookmark.owner != identity.username {
        return Err(error::not_found(&state, &headers, &uri));
    }
    db_result(
        &state,
        &headers,
        &uri,
        sqlx::query("delete from bookmarks where id = $1")
            .bind(id)
            .execute(&state.pool)
            .await,
    )?;
    Ok(error::empty(StatusCode::NO_CONTENT))
}

async fn bookmark_by_id(pool: &PgPool, id: Uuid) -> Result<Bookmark, sqlx::Error> {
    sqlx::query_as::<_, Bookmark>(AssertSqlSafe(format!(
        "select {BOOKMARK_COLUMNS} from bookmarks where id = $1"
    )))
    .bind(id)
    .fetch_one(pool)
    .await
}

async fn bookmark_by_id_tx(
    tx: &mut Transaction<'_, Postgres>,
    id: Uuid,
    lock: bool,
) -> Result<Bookmark, sqlx::Error> {
    let suffix = if lock { " for update" } else { "" };
    sqlx::query_as::<_, Bookmark>(AssertSqlSafe(format!(
        "select {BOOKMARK_COLUMNS} from bookmarks where id = $1{suffix}"
    )))
    .bind(id)
    .fetch_one(&mut **tx)
    .await
}

fn bookmark_visible_to(bookmark: &Bookmark, caller: &str) -> bool {
    bookmark.owner == caller || (bookmark.visibility == "public" && bookmark.status == "active")
}

#[derive(Serialize)]
struct TagsResponse {
    tags: Vec<TagCount>,
}

#[derive(Serialize, FromRow)]
struct TagCount {
    tag: String,
    count: i64,
}

async fn list_tags(
    State(state): State<AppState>,
    headers: HeaderMap,
    uri: Uri,
    identity: Option<Extension<Identity>>,
) -> HandlerResult {
    let identity = require_identity(&state, &headers, &uri, identity).await?;
    let rows = db_result(
        &state,
        &headers,
        &uri,
        sqlx::query_as::<_, TagCount>(
            r#"select t.tag, count(*)::bigint as count
           from bookmarks b cross join unnest(b.tags) as t(tag)
           where b.owner = $1
           group by t.tag
           order by count(*) desc, t.tag"#,
        )
        .bind(&identity.username)
        .fetch_all(&state.pool)
        .await,
    )?;
    Ok(error::json(StatusCode::OK, &TagsResponse { tags: rows }))
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
struct MessageRequest {
    key: Option<String>,
    language: Option<String>,
    text: Option<String>,
    description: Option<String>,
}

struct ValidMessage {
    key: String,
    language: String,
    text: String,
    description: Option<String>,
}

fn validate_message(body: MessageRequest) -> Result<ValidMessage, Vec<FieldViolation>> {
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

#[derive(Debug, FromRow, Clone)]
struct ReportRow {
    id: Uuid,
    bookmark_id: Uuid,
    reporter: String,
    reason: String,
    comment: Option<String>,
    status: String,
    resolved_by: Option<String>,
    resolved_at: Option<DateTime<Utc>>,
    resolution_note: Option<String>,
    created_at: DateTime<Utc>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ReportResponse {
    id: Uuid,
    bookmark_id: Uuid,
    reporter: String,
    reason: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    comment: Option<String>,
    status: String,
    created_at: DateTime<Utc>,
    #[serde(skip_serializing_if = "Option::is_none")]
    resolved_by: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    resolved_at: Option<DateTime<Utc>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    resolution_note: Option<String>,
}

impl From<ReportRow> for ReportResponse {
    fn from(report: ReportRow) -> Self {
        Self {
            id: report.id,
            bookmark_id: report.bookmark_id,
            reporter: report.reporter,
            reason: report.reason,
            comment: report.comment,
            status: report.status,
            created_at: report.created_at,
            resolved_by: report.resolved_by,
            resolved_at: report.resolved_at,
            resolution_note: report.resolution_note,
        }
    }
}

#[derive(Deserialize)]
struct ReportRequest {
    reason: Option<String>,
    comment: Option<String>,
}

#[derive(Deserialize)]
struct ResolutionRequest {
    resolution: Option<String>,
    note: Option<String>,
}

#[derive(Deserialize)]
struct BookmarkStatusRequest {
    status: Option<String>,
    note: Option<String>,
}

fn valid_report_reason(reason: &str) -> bool {
    matches!(reason, "spam" | "offensive" | "broken-link" | "other")
}

fn valid_report_status(status: &str) -> bool {
    matches!(status, "open" | "dismissed" | "actioned")
}

fn validate_report(body: &ReportRequest) -> Result<String, Vec<FieldViolation>> {
    let mut validator = Validator::default();
    let reason = body.reason.clone().unwrap_or_default();
    validator.check(
        valid_report_reason(&reason),
        "reason",
        "validation.report.reason.invalid",
    );
    validator.check(
        error::rune_len(&body.comment) <= 1000,
        "comment",
        "validation.report.comment.too-long",
    );
    if validator.is_empty() {
        Ok(reason)
    } else {
        Err(validator.into_fields())
    }
}

async fn report_bookmark(
    State(state): State<AppState>,
    headers: HeaderMap,
    uri: Uri,
    Path(bookmark_id): Path<Uuid>,
    identity: Option<Extension<Identity>>,
    body: Result<Json<ReportRequest>, JsonRejection>,
) -> HandlerResult {
    let reporter = require_identity(&state, &headers, &uri, identity)
        .await?
        .username;
    let bookmark = db_result(
        &state,
        &headers,
        &uri,
        bookmark_by_id(&state.pool, bookmark_id).await,
    )?;
    if bookmark.visibility != "public" || bookmark.status != "active" {
        return Err(error::not_found(&state, &headers, &uri));
    }
    let body = json_body(&state, &headers, &uri, body).await?;
    let reason = validation_result(&state, &headers, &uri, validate_report(&body)).await?;
    let exists = sqlx::query_scalar::<_, bool>(
        "select exists (select 1 from reports where bookmark_id = $1 and reporter = $2 and status = 'open')",
    )
    .bind(bookmark_id)
    .bind(&reporter)
    .fetch_one(&state.pool)
    .await
    .unwrap_or(false);
    if exists {
        return Err(error::conflict(
            &state,
            &headers,
            &uri,
            "You already have an open report on this bookmark.",
        ));
    }
    let report = ReportRow {
        id: Uuid::new_v4(),
        bookmark_id,
        reporter,
        reason,
        comment: body.comment,
        status: "open".to_string(),
        resolved_by: None,
        resolved_at: None,
        resolution_note: None,
        created_at: error::now_utc(),
    };
    let inserted = sqlx::query(
        "insert into reports (id, bookmark_id, reporter, reason, comment, status, created_at) values ($1, $2, $3, $4, $5, $6, $7)",
    )
    .bind(report.id)
    .bind(report.bookmark_id)
    .bind(&report.reporter)
    .bind(&report.reason)
    .bind(&report.comment)
    .bind(&report.status)
    .bind(report.created_at)
    .execute(&state.pool)
    .await;
    if let Err(err) = inserted {
        if db::pg_unique_violation(&err) {
            return Err(error::conflict(
                &state,
                &headers,
                &uri,
                "You already have an open report on this bookmark.",
            ));
        }
        return Err(db_error(&state, &headers, &uri, err));
    }
    tracing::info!(
        event = "report_created",
        outcome = "success",
        actor = %report.reporter,
        resource_type = "report",
        resource_id = %report.id,
        bookmark_id = %report.bookmark_id,
        reason = %report.reason,
        "Report created"
    );
    Ok(error::json(
        StatusCode::CREATED,
        &ReportResponse::from(report),
    ))
}

async fn list_my_reports(
    State(state): State<AppState>,
    headers: HeaderMap,
    uri: Uri,
    identity: Option<Extension<Identity>>,
) -> HandlerResult {
    let reporter = require_identity(&state, &headers, &uri, identity)
        .await?
        .username;
    let params = error::parse_query(&uri);
    let (page, size) = match error::parse_page(&params) {
        Ok(value) => value,
        Err(detail) => return Err(bad_request(&state, &headers, &uri, detail)),
    };
    let status = error::first(&params, "status");
    if !status.is_empty() && !valid_report_status(&status) {
        return Err(bad_request(
            &state,
            &headers,
            &uri,
            "status must be one of: open, dismissed, actioned",
        ));
    }
    let query = ReportPageQuery {
        where_sql: "where reporter = $1 and ($2 = '' or status = $2)",
        args: vec![reporter, status],
        order_sql: "order by created_at desc, id desc",
        page,
        size,
    };
    report_page(&state, &headers, &uri, query).await
}

async fn update_my_report(
    State(state): State<AppState>,
    headers: HeaderMap,
    uri: Uri,
    Path(id): Path<Uuid>,
    identity: Option<Extension<Identity>>,
    body: Result<Json<ReportRequest>, JsonRejection>,
) -> HandlerResult {
    let reporter = require_identity(&state, &headers, &uri, identity)
        .await?
        .username;
    let body = json_body(&state, &headers, &uri, body).await?;
    let reason = validation_result(&state, &headers, &uri, validate_report(&body)).await?;
    let mut tx = db_result(&state, &headers, &uri, state.pool.begin().await)?;
    let mut report = db_result(
        &state,
        &headers,
        &uri,
        report_by_id_tx(&mut tx, id, true).await,
    )?;
    if report.reporter != reporter {
        return Err(error::not_found(&state, &headers, &uri));
    }
    if report.status != "open" {
        return Err(error::conflict(
            &state,
            &headers,
            &uri,
            "The report has already been resolved.",
        ));
    }
    report.reason = reason;
    report.comment = body.comment;
    db_result(
        &state,
        &headers,
        &uri,
        sqlx::query("update reports set reason = $2, comment = $3 where id = $1")
            .bind(report.id)
            .bind(&report.reason)
            .bind(&report.comment)
            .execute(&mut *tx)
            .await,
    )?;
    db_result(&state, &headers, &uri, tx.commit().await)?;
    tracing::info!(
        event = "report_updated",
        outcome = "success",
        actor = %report.reporter,
        resource_type = "report",
        resource_id = %report.id,
        bookmark_id = %report.bookmark_id,
        reason = %report.reason,
        "Report updated by its reporter"
    );
    Ok(error::json(StatusCode::OK, &ReportResponse::from(report)))
}

async fn withdraw_report(
    State(state): State<AppState>,
    headers: HeaderMap,
    uri: Uri,
    Path(id): Path<Uuid>,
    identity: Option<Extension<Identity>>,
) -> HandlerResult {
    let reporter = require_identity(&state, &headers, &uri, identity)
        .await?
        .username;
    let mut tx = db_result(&state, &headers, &uri, state.pool.begin().await)?;
    let report = db_result(
        &state,
        &headers,
        &uri,
        report_by_id_tx(&mut tx, id, true).await,
    )?;
    if report.reporter != reporter {
        return Err(error::not_found(&state, &headers, &uri));
    }
    if report.status != "open" {
        return Err(error::conflict(
            &state,
            &headers,
            &uri,
            "The report has already been resolved.",
        ));
    }
    db_result(
        &state,
        &headers,
        &uri,
        sqlx::query("delete from reports where id = $1")
            .bind(report.id)
            .execute(&mut *tx)
            .await,
    )?;
    db_result(&state, &headers, &uri, tx.commit().await)?;
    tracing::info!(
        event = "report_withdrawn",
        outcome = "success",
        actor = %report.reporter,
        resource_type = "report",
        resource_id = %report.id,
        bookmark_id = %report.bookmark_id,
        "Report withdrawn by its reporter"
    );
    Ok(error::empty(StatusCode::NO_CONTENT))
}

async fn list_report_queue(
    State(state): State<AppState>,
    headers: HeaderMap,
    uri: Uri,
    identity: Option<Extension<Identity>>,
) -> HandlerResult {
    require_role(&state, &headers, &uri, identity, "moderator").await?;
    let params = error::parse_query(&uri);
    let (page, size) = match error::parse_page(&params) {
        Ok(value) => value,
        Err(detail) => return Err(bad_request(&state, &headers, &uri, detail)),
    };
    let mut status = error::first(&params, "status");
    if status.is_empty() {
        status = "open".to_string();
    }
    if !valid_report_status(&status) {
        return Err(bad_request(
            &state,
            &headers,
            &uri,
            "status must be one of: open, dismissed, actioned",
        ));
    }
    let query = ReportPageQuery {
        where_sql: "where status = $1",
        args: vec![status],
        order_sql: "order by created_at, id",
        page,
        size,
    };
    report_page(&state, &headers, &uri, query).await
}

struct ReportPageQuery {
    where_sql: &'static str,
    args: Vec<String>,
    order_sql: &'static str,
    page: i64,
    size: i64,
}

async fn report_page(
    state: &AppState,
    headers: &HeaderMap,
    uri: &Uri,
    query: ReportPageQuery,
) -> HandlerResult {
    let ReportPageQuery {
        where_sql,
        args,
        order_sql,
        page,
        size,
    } = query;
    let mut count = sqlx::query_scalar::<_, i64>(AssertSqlSafe(format!(
        "select count(*) from reports {where_sql}"
    )));
    for arg in &args {
        count = count.bind(arg);
    }
    let total = db_result(state, headers, uri, count.fetch_one(&state.pool).await)?;
    let mut query = sqlx::query_as::<_, ReportRow>(AssertSqlSafe(format!(
        "select {REPORT_COLUMNS} from reports {where_sql} {order_sql} limit ${} offset ${}",
        args.len() + 1,
        args.len() + 2
    )));
    for arg in &args {
        query = query.bind(arg);
    }
    let rows = db_result(
        state,
        headers,
        uri,
        query
            .bind(size)
            .bind(page * size)
            .fetch_all(&state.pool)
            .await,
    )?;
    let items = rows
        .into_iter()
        .map(ReportResponse::from)
        .collect::<Vec<_>>();
    Ok(error::json(
        StatusCode::OK,
        &error::page_response(items, page, size, total),
    ))
}

async fn report_by_id_tx(
    tx: &mut Transaction<'_, Postgres>,
    id: Uuid,
    lock: bool,
) -> Result<ReportRow, sqlx::Error> {
    let suffix = if lock { " for update" } else { "" };
    sqlx::query_as::<_, ReportRow>(AssertSqlSafe(format!(
        "select {REPORT_COLUMNS} from reports where id = $1{suffix}"
    )))
    .bind(id)
    .fetch_one(&mut **tx)
    .await
}

async fn resolve_report(
    State(state): State<AppState>,
    headers: HeaderMap,
    uri: Uri,
    Path(id): Path<Uuid>,
    identity: Option<Extension<Identity>>,
    body: Result<Json<ResolutionRequest>, JsonRejection>,
) -> HandlerResult {
    let actor = require_role(&state, &headers, &uri, identity, "moderator")
        .await?
        .username;
    let body = json_body(&state, &headers, &uri, body).await?;
    let resolution = body.resolution.unwrap_or_default();
    let mut validator = Validator::default();
    validator.check(
        valid_report_status(&resolution),
        "resolution",
        "validation.resolution.invalid",
    );
    validator.check(
        error::rune_len(&body.note) <= 1000,
        "note",
        "validation.resolution.note.too-long",
    );
    if !validator.is_empty() {
        return Err(
            error::validation_problem(&state, &headers, &uri, validator.into_fields()).await,
        );
    }

    let mut tx = db_result(&state, &headers, &uri, state.pool.begin().await)?;
    if resolution == "actioned" {
        let bookmark_id = db_result(
            &state,
            &headers,
            &uri,
            sqlx::query_scalar::<_, Uuid>("select bookmark_id from reports where id = $1")
                .bind(id)
                .fetch_one(&mut *tx)
                .await,
        )?;
        db_result(
            &state,
            &headers,
            &uri,
            sqlx::query("select id from bookmarks where id = $1 for update")
                .bind(bookmark_id)
                .execute(&mut *tx)
                .await,
        )?;
    }
    let locked = db_result(
        &state,
        &headers,
        &uri,
        report_by_id_tx(&mut tx, id, true).await,
    )?;
    let resolved = if resolution == "open" {
        let Some(report) = db_result(
            &state,
            &headers,
            &uri,
            reopen_report(&mut tx, &actor, locked).await,
        )?
        else {
            return Err(error::conflict(
                &state,
                &headers,
                &uri,
                "The reporter already has another open report on this bookmark.",
            ));
        };
        report
    } else {
        let primary = db_result(
            &state,
            &headers,
            &uri,
            resolve_one_report(
                &mut tx,
                &actor,
                locked,
                &resolution,
                body.note.clone(),
                false,
            )
            .await,
        )?;
        if resolution == "actioned" {
            db_result(
                &state,
                &headers,
                &uri,
                hide_bookmark_tx(&mut tx, &actor, primary.bookmark_id, body.note.clone()).await,
            )?;
            let siblings = db_result(&state, &headers, &uri, sqlx::query_as::<_, ReportRow>(AssertSqlSafe(format!(
                "select {REPORT_COLUMNS} from reports where bookmark_id = $1 and status = 'open' and id <> $2 order by id for update"
            )))
            .bind(primary.bookmark_id)
            .bind(primary.id)
            .fetch_all(&mut *tx)
            .await)?;
            for sibling in siblings {
                db_result(
                    &state,
                    &headers,
                    &uri,
                    resolve_one_report(
                        &mut tx,
                        &actor,
                        sibling,
                        "actioned",
                        body.note.clone(),
                        true,
                    )
                    .await,
                )?;
            }
        }
        primary
    };
    db_result(&state, &headers, &uri, tx.commit().await)?;
    Ok(error::json(StatusCode::OK, &ReportResponse::from(resolved)))
}

async fn reopen_report(
    tx: &mut Transaction<'_, Postgres>,
    actor: &str,
    mut report: ReportRow,
) -> Result<Option<ReportRow>, sqlx::Error> {
    let duplicate = sqlx::query_scalar::<_, bool>(
        "select exists (select 1 from reports where bookmark_id = $1 and reporter = $2 and status = 'open' and id <> $3)",
    )
    .bind(report.bookmark_id)
    .bind(&report.reporter)
    .bind(report.id)
    .fetch_one(&mut **tx)
    .await?;
    if duplicate {
        return Ok(None);
    }
    report.status = "open".to_string();
    report.resolved_by = None;
    report.resolved_at = None;
    report.resolution_note = None;
    sqlx::query(
        "update reports set status = 'open', resolved_by = null, resolved_at = null, resolution_note = null where id = $1",
    )
    .bind(report.id)
    .execute(&mut **tx)
    .await?;
    audit_tx(
        tx,
        actor,
        "report.reopened",
        "report",
        &report.id.to_string(),
        Some(json!({ "bookmarkId": report.bookmark_id.to_string() })),
    )
    .await?;
    tracing::info!(
        event = "report_reopened",
        outcome = "success",
        actor = %actor,
        resource_type = "report",
        resource_id = %report.id,
        bookmark_id = %report.bookmark_id,
        "Report re-opened"
    );
    Ok(Some(report))
}

async fn resolve_one_report(
    tx: &mut Transaction<'_, Postgres>,
    actor: &str,
    mut report: ReportRow,
    resolution: &str,
    note: Option<String>,
    auto_resolved: bool,
) -> Result<ReportRow, sqlx::Error> {
    let now = error::now_utc();
    report.status = resolution.to_string();
    report.resolved_by = Some(actor.to_string());
    report.resolved_at = Some(now);
    report.resolution_note = note.clone();
    sqlx::query(
        "update reports set status = $2, resolved_by = $3, resolved_at = $4, resolution_note = $5 where id = $1",
    )
    .bind(report.id)
    .bind(resolution)
    .bind(actor)
    .bind(now)
    .bind(&note)
    .execute(&mut **tx)
    .await?;
    audit_tx(
        tx,
        actor,
        "report.resolved",
        "report",
        &report.id.to_string(),
        Some(json!({
            "bookmarkId": report.bookmark_id.to_string(),
            "resolution": resolution,
            "note": note,
            "autoResolved": auto_resolved
        })),
    )
    .await?;
    tracing::info!(
        event = "report_resolved",
        outcome = "success",
        actor = %actor,
        resource_type = "report",
        resource_id = %report.id,
        bookmark_id = %report.bookmark_id,
        resolution = %resolution,
        auto_resolved = auto_resolved,
        "Report resolved"
    );
    Ok(report)
}

async fn hide_bookmark_tx(
    tx: &mut Transaction<'_, Postgres>,
    actor: &str,
    bookmark_id: Uuid,
    note: Option<String>,
) -> Result<(), sqlx::Error> {
    let bookmark = bookmark_by_id_tx(tx, bookmark_id, false).await?;
    if bookmark.status == "hidden" {
        return Ok(());
    }
    sqlx::query("update bookmarks set status = 'hidden', updated_at = $2 where id = $1")
        .bind(bookmark_id)
        .bind(error::now_utc())
        .execute(&mut **tx)
        .await?;
    audit_tx(
        tx,
        actor,
        "bookmark.status-changed",
        "bookmark",
        &bookmark_id.to_string(),
        Some(json!({ "from": "active", "to": "hidden", "note": note })),
    )
    .await?;
    tracing::info!(
        event = "bookmark_status_changed",
        outcome = "success",
        actor = %actor,
        resource_type = "bookmark",
        resource_id = %bookmark_id,
        from = "active",
        to = "hidden",
        "Bookmark hidden by an actioned report"
    );
    Ok(())
}

async fn set_bookmark_status(
    State(state): State<AppState>,
    headers: HeaderMap,
    uri: Uri,
    Path(id): Path<Uuid>,
    identity: Option<Extension<Identity>>,
    body: Result<Json<BookmarkStatusRequest>, JsonRejection>,
) -> HandlerResult {
    let actor = require_role(&state, &headers, &uri, identity, "moderator")
        .await?
        .username;
    let body = json_body(&state, &headers, &uri, body).await?;
    let mut validator = Validator::default();
    let status = body.status.unwrap_or_default();
    validator.check(
        matches!(status.as_str(), "active" | "hidden"),
        "status",
        "validation.bookmark-status.invalid",
    );
    validator.check(
        error::rune_len(&body.note) <= 1000,
        "note",
        "validation.bookmark-status.note.too-long",
    );
    if !validator.is_empty() {
        return Err(
            error::validation_problem(&state, &headers, &uri, validator.into_fields()).await,
        );
    }
    let mut tx = db_result(&state, &headers, &uri, state.pool.begin().await)?;
    let mut bookmark = db_result(
        &state,
        &headers,
        &uri,
        bookmark_by_id_tx(&mut tx, id, true).await,
    )?;
    let previous = bookmark.status.clone();
    bookmark.status = status;
    bookmark.updated_at = error::now_utc();
    db_result(
        &state,
        &headers,
        &uri,
        sqlx::query("update bookmarks set status = $2, updated_at = $3 where id = $1")
            .bind(bookmark.id)
            .bind(&bookmark.status)
            .bind(bookmark.updated_at)
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
            "bookmark.status-changed",
            "bookmark",
            &bookmark.id.to_string(),
            Some(json!({ "from": previous, "to": bookmark.status, "note": body.note })),
        )
        .await,
    )?;
    db_result(&state, &headers, &uri, tx.commit().await)?;
    tracing::info!(
        event = "bookmark_status_changed",
        outcome = "success",
        actor = %actor,
        resource_type = "bookmark",
        resource_id = %bookmark.id,
        from = %previous,
        to = %bookmark.status,
        "Bookmark moderation status changed"
    );
    Ok(error::json(
        StatusCode::OK,
        &BookmarkResponse::from(bookmark),
    ))
}

#[derive(Debug, FromRow)]
struct UserAccountRow {
    username: String,
    first_seen: DateTime<Utc>,
    last_seen: DateTime<Utc>,
    status: String,
    blocked_reason: Option<String>,
    bookmark_count: i64,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct UserAccountResponse {
    username: String,
    first_seen: DateTime<Utc>,
    last_seen: DateTime<Utc>,
    status: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    blocked_reason: Option<String>,
    bookmark_count: i64,
}

impl From<UserAccountRow> for UserAccountResponse {
    fn from(row: UserAccountRow) -> Self {
        Self {
            username: row.username,
            first_seen: row.first_seen,
            last_seen: row.last_seen,
            status: row.status,
            blocked_reason: row.blocked_reason,
            bookmark_count: row.bookmark_count,
        }
    }
}

async fn list_users(
    State(state): State<AppState>,
    headers: HeaderMap,
    uri: Uri,
    identity: Option<Extension<Identity>>,
) -> HandlerResult {
    require_role(&state, &headers, &uri, identity, "admin").await?;
    let params = error::parse_query(&uri);
    let (page, size) = match error::parse_page(&params) {
        Ok(value) => value,
        Err(detail) => return Err(bad_request(&state, &headers, &uri, detail)),
    };
    let q = error::first(&params, "q");
    if q.chars().count() > 100 {
        return Err(bad_request(
            &state,
            &headers,
            &uri,
            "q must be at most 100 characters",
        ));
    }
    let status = error::first(&params, "status");
    if !status.is_empty() && status != "active" && status != "blocked" {
        return Err(bad_request(
            &state,
            &headers,
            &uri,
            "status must be one of: active, blocked",
        ));
    }
    let q_like = if q.is_empty() {
        String::new()
    } else {
        format!("%{}%", error::escape_like(&q.to_ascii_lowercase()))
    };
    let where_sql =
        "where ($1 = '' or lower(username) like $1 escape '\\') and ($2 = '' or status = $2)";
    let total = db_result(
        &state,
        &headers,
        &uri,
        sqlx::query_scalar::<_, i64>(AssertSqlSafe(format!(
            "select count(*) from user_accounts {where_sql}"
        )))
        .bind(&q_like)
        .bind(&status)
        .fetch_one(&state.pool)
        .await,
    )?;
    let rows = db_result(&state, &headers, &uri, sqlx::query_as::<_, UserAccountRow>(AssertSqlSafe(format!(
        "select username, first_seen, last_seen, status, blocked_reason, (select count(*) from bookmarks b where b.owner = u.username)::bigint as bookmark_count from user_accounts u {where_sql} order by last_seen desc limit $3 offset $4"
    )))
    .bind(&q_like)
    .bind(&status)
    .bind(size)
    .bind(page * size)
    .fetch_all(&state.pool)
    .await)?;
    let items = rows
        .into_iter()
        .map(UserAccountResponse::from)
        .collect::<Vec<_>>();
    Ok(error::json(
        StatusCode::OK,
        &error::page_response(items, page, size, total),
    ))
}

async fn get_user(
    State(state): State<AppState>,
    headers: HeaderMap,
    uri: Uri,
    Path(username): Path<String>,
    identity: Option<Extension<Identity>>,
) -> HandlerResult {
    require_role(&state, &headers, &uri, identity, "admin").await?;
    let row = db_result(
        &state,
        &headers,
        &uri,
        user_account(&state.pool, &username).await,
    )?;
    Ok(error::json(StatusCode::OK, &UserAccountResponse::from(row)))
}

#[derive(Deserialize)]
struct UserStatusRequest {
    status: Option<String>,
    reason: Option<String>,
}

async fn set_user_status(
    State(state): State<AppState>,
    headers: HeaderMap,
    uri: Uri,
    Path(username): Path<String>,
    identity: Option<Extension<Identity>>,
    body: Result<Json<UserStatusRequest>, JsonRejection>,
) -> HandlerResult {
    let actor = require_role(&state, &headers, &uri, identity, "admin")
        .await?
        .username;
    let body = json_body(&state, &headers, &uri, body).await?;
    let status = body.status.unwrap_or_default();
    if status != "active" && status != "blocked" {
        return Err(bad_request(
            &state,
            &headers,
            &uri,
            "status must be one of: active, blocked",
        ));
    }
    db_result(
        &state,
        &headers,
        &uri,
        user_account(&state.pool, &username).await,
    )?;
    let reason = body.reason.map(|reason| reason.trim().to_string());
    if status == "blocked" {
        let mut validator = Validator::default();
        validator.check(
            reason.as_ref().is_some_and(|reason| !reason.is_empty()),
            "reason",
            "validation.block.reason.required",
        );
        validator.check(
            error::rune_len(&reason) <= 1000,
            "reason",
            "validation.block.reason.too-long",
        );
        if !validator.is_empty() {
            return Err(
                error::validation_problem(&state, &headers, &uri, validator.into_fields()).await,
            );
        }
        if username == actor {
            return Err(error::conflict(
                &state,
                &headers,
                &uri,
                "Admins cannot block themselves.",
            ));
        }
    }
    let mut tx = db_result(&state, &headers, &uri, state.pool.begin().await)?;
    db_result(
        &state,
        &headers,
        &uri,
        sqlx::query(
            "update user_accounts set status = $2, blocked_reason = $3 where username = $1",
        )
        .bind(&username)
        .bind(&status)
        .bind(if status == "blocked" {
            reason.as_ref()
        } else {
            None
        })
        .execute(&mut *tx)
        .await,
    )?;
    let action = if status == "blocked" {
        "user.blocked"
    } else {
        "user.unblocked"
    };
    let detail = if status == "blocked" {
        Some(json!({ "reason": reason }))
    } else {
        None
    };
    db_result(
        &state,
        &headers,
        &uri,
        audit_tx(&mut tx, &actor, action, "user", &username, detail).await,
    )?;
    db_result(&state, &headers, &uri, tx.commit().await)?;
    tracing::info!(
        event = if status == "blocked" { "user_blocked" } else { "user_unblocked" },
        outcome = "success",
        actor = %actor,
        resource_type = "user",
        resource_id = %username,
        "User account status changed"
    );
    let row = db_result(
        &state,
        &headers,
        &uri,
        user_account(&state.pool, &username).await,
    )?;
    Ok(error::json(StatusCode::OK, &UserAccountResponse::from(row)))
}

async fn user_account(pool: &PgPool, username: &str) -> Result<UserAccountRow, sqlx::Error> {
    sqlx::query_as::<_, UserAccountRow>(
        "select username, first_seen, last_seen, status, blocked_reason, (select count(*) from bookmarks b where b.owner = u.username)::bigint as bookmark_count from user_accounts u where username = $1",
    )
    .bind(username)
    .fetch_one(pool)
    .await
}

#[derive(Serialize, FromRow)]
#[serde(rename_all = "camelCase")]
struct AuditEntryResponse {
    id: Uuid,
    actor: String,
    action: String,
    target_type: String,
    target_id: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    detail: Option<Value>,
    created_at: DateTime<Utc>,
}

async fn list_audit_log(
    State(state): State<AppState>,
    headers: HeaderMap,
    uri: Uri,
    identity: Option<Extension<Identity>>,
) -> HandlerResult {
    require_role(&state, &headers, &uri, identity, "admin").await?;
    let params = error::parse_query(&uri);
    let (page, size) = match error::parse_page(&params) {
        Ok(value) => value,
        Err(detail) => return Err(bad_request(&state, &headers, &uri, detail)),
    };
    let from = match parse_time_param(&params, "from") {
        Ok(value) => value,
        Err(detail) => return Err(bad_request(&state, &headers, &uri, detail)),
    };
    let to = match parse_time_param(&params, "to") {
        Ok(value) => value,
        Err(detail) => return Err(bad_request(&state, &headers, &uri, detail)),
    };
    let actor = error::first(&params, "actor");
    let action = error::first(&params, "action");
    let target_type = error::first(&params, "targetType");
    let target_id = error::first(&params, "targetId");

    let where_sql = "where ($1 = '' or actor = $1) and ($2 = '' or action = $2) and ($3 = '' or target_type = $3) and ($4 = '' or target_id = $4) and ($5::timestamptz is null or created_at >= $5) and ($6::timestamptz is null or created_at <= $6)";
    let total = db_result(
        &state,
        &headers,
        &uri,
        sqlx::query_scalar::<_, i64>(AssertSqlSafe(format!(
            "select count(*) from audit_entries {where_sql}"
        )))
        .bind(&actor)
        .bind(&action)
        .bind(&target_type)
        .bind(&target_id)
        .bind(from)
        .bind(to)
        .fetch_one(&state.pool)
        .await,
    )?;
    let rows = db_result(&state, &headers, &uri, sqlx::query_as::<_, AuditEntryResponse>(AssertSqlSafe(format!(
        "select id, actor, action, target_type, target_id, detail, created_at from audit_entries {where_sql} order by created_at desc, id desc limit $7 offset $8"
    )))
    .bind(&actor)
    .bind(&action)
    .bind(&target_type)
    .bind(&target_id)
    .bind(from)
    .bind(to)
    .bind(size)
    .bind(page * size)
    .fetch_all(&state.pool)
    .await)?;
    Ok(error::json(
        StatusCode::OK,
        &error::page_response(rows, page, size, total),
    ))
}

fn parse_time_param(
    params: &std::collections::HashMap<String, Vec<String>>,
    name: &str,
) -> Result<Option<DateTime<Utc>>, String> {
    let raw = error::first(params, name);
    if raw.is_empty() {
        return Ok(None);
    }
    DateTime::parse_from_rfc3339(&raw)
        .map(|value| Some(value.with_timezone(&Utc)))
        .map_err(|_| format!("{name} must be an RFC 3339 timestamp"))
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct StatsResponse {
    totals: StatsTotals,
    daily: Vec<DailyStat>,
    top_tags: Vec<TagCount>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct StatsTotals {
    users: i64,
    bookmarks: i64,
    public_bookmarks: i64,
    hidden_bookmarks: i64,
    open_reports: i64,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct DailyStat {
    date: String,
    bookmarks_created: i64,
    active_users: i64,
}

async fn get_stats(
    State(state): State<AppState>,
    headers: HeaderMap,
    uri: Uri,
    identity: Option<Extension<Identity>>,
) -> HandlerResult {
    require_role(&state, &headers, &uri, identity, "moderator").await?;
    let row = db_result(
        &state,
        &headers,
        &uri,
        sqlx::query(
            r#"select
           (select count(*) from user_accounts)::bigint as users,
           (select count(*) from bookmarks)::bigint as bookmarks,
           (select count(*) from bookmarks where visibility = 'public')::bigint as public_bookmarks,
           (select count(*) from bookmarks where status = 'hidden')::bigint as hidden_bookmarks,
           (select count(*) from reports where status = 'open')::bigint as open_reports"#,
        )
        .fetch_one(&state.pool)
        .await,
    )?;
    let totals = StatsTotals {
        users: row.try_get("users").unwrap_or(0),
        bookmarks: row.try_get("bookmarks").unwrap_or(0),
        public_bookmarks: row.try_get("public_bookmarks").unwrap_or(0),
        hidden_bookmarks: row.try_get("hidden_bookmarks").unwrap_or(0),
        open_reports: row.try_get("open_reports").unwrap_or(0),
    };
    let today = Utc::now().date_naive();
    let from_date = today.checked_sub_days(Days::new(29)).unwrap_or(today);
    let from = Utc.from_utc_datetime(&from_date.and_hms_opt(0, 0, 0).unwrap());
    let bookmark_counts = db_result(
        &state,
        &headers,
        &uri,
        count_per_day(&state.pool, "bookmarks", "created_at", from).await,
    )?;
    let active_counts = db_result(
        &state,
        &headers,
        &uri,
        count_per_day(&state.pool, "user_accounts", "last_seen", from).await,
    )?;
    let mut daily = Vec::with_capacity(30);
    for offset in 0..30 {
        let day = from_date
            .checked_add_days(Days::new(offset))
            .unwrap_or(from_date);
        let key = day.format("%Y-%m-%d").to_string();
        daily.push(DailyStat {
            date: key.clone(),
            bookmarks_created: bookmark_counts.get(&key).copied().unwrap_or(0),
            active_users: active_counts.get(&key).copied().unwrap_or(0),
        });
    }
    let top_tags = db_result(
        &state,
        &headers,
        &uri,
        sqlx::query_as::<_, TagCount>(
            r#"select t.tag, count(*)::bigint as count
           from bookmarks b cross join unnest(b.tags) as t(tag)
           group by t.tag
           order by count(*) desc, t.tag
           limit 10"#,
        )
        .fetch_all(&state.pool)
        .await,
    )?;
    Ok(error::etag_json(
        &headers,
        StatusCode::OK,
        &StatsResponse {
            totals,
            daily,
            top_tags,
        },
        &[],
    ))
}

async fn count_per_day(
    pool: &PgPool,
    table: &str,
    column: &str,
    from: DateTime<Utc>,
) -> Result<BTreeMap<String, i64>, sqlx::Error> {
    let sql = format!(
        "select ({column} at time zone 'UTC')::date::text as day, count(*)::bigint as count from {table} where {column} >= $1 group by day"
    );
    let rows = sqlx::query(AssertSqlSafe(sql))
        .bind(from)
        .fetch_all(pool)
        .await?;
    let mut counts = BTreeMap::new();
    for row in rows {
        let day: String = row.try_get("day")?;
        let count: i64 = row.try_get("count")?;
        counts.insert(day, count);
    }
    Ok(counts)
}

async fn audit_tx(
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

#[cfg(test)]
mod tests {
    use base64::Engine;
    use chrono::{TimeZone, Utc};
    use serde_json::json;
    use uuid::Uuid;

    use super::{
        Bookmark, BookmarkRequest, BookmarkResponse, Cursor, MessageRequest, ReportRequest,
        bookmark_visible_to, encode_cursor, parse_time_param, validate_bookmark, validate_message,
        validate_report,
    };

    fn bookmark(owner: &str, visibility: &str, status: &str, tags: Vec<&str>) -> Bookmark {
        Bookmark {
            id: Uuid::parse_str("00000000-0000-4000-8000-000000000010").unwrap(),
            owner: owner.to_string(),
            url: "https://example.com".to_string(),
            title: "Example".to_string(),
            notes: None,
            tags: tags.into_iter().map(ToString::to_string).collect(),
            visibility: visibility.to_string(),
            status: status.to_string(),
            created_at: Utc.with_ymd_and_hms(2026, 7, 5, 12, 0, 0).unwrap(),
            updated_at: Utc.with_ymd_and_hms(2026, 7, 5, 12, 0, 0).unwrap(),
        }
    }

    #[test]
    fn validate_bookmark_normalizes_tags_and_defaults_visibility() {
        let bookmark = validate_bookmark(BookmarkRequest {
            url: Some("https://example.com/path".to_string()),
            title: Some(" Example ".to_string()),
            notes: None,
            tags: vec![
                " Rust ".to_string(),
                "rust".to_string(),
                "web-dev".to_string(),
            ],
            visibility: None,
        })
        .unwrap();

        assert_eq!(bookmark.url, "https://example.com/path");
        assert_eq!(bookmark.title, "Example");
        assert_eq!(
            bookmark.tags,
            vec!["rust".to_string(), "web-dev".to_string()]
        );
        assert_eq!(bookmark.visibility, "private");
    }

    #[test]
    fn validate_bookmark_collects_field_errors() {
        let errors = match validate_bookmark(BookmarkRequest {
            url: Some("ftp://example.com".to_string()),
            title: Some("".to_string()),
            notes: Some("n".repeat(4001)),
            tags: vec!["Bad Tag".to_string()],
            visibility: Some("shared".to_string()),
        }) {
            Ok(_) => panic!("invalid bookmark should be rejected"),
            Err(errors) => errors,
        };

        let keys = errors
            .into_iter()
            .map(|field| field.message_key)
            .collect::<Vec<_>>();
        assert!(keys.contains(&"validation.url.invalid"));
        assert!(keys.contains(&"validation.title.required"));
        assert!(keys.contains(&"validation.notes.too-long"));
        assert!(keys.contains(&"validation.tag.invalid"));
        assert!(keys.contains(&"validation.visibility.invalid"));
    }

    #[test]
    fn bookmark_response_sorts_tags_and_omits_absent_notes() {
        let value = serde_json::to_value(BookmarkResponse::from(bookmark(
            "alice",
            "public",
            "active",
            vec!["zeta", "alpha"],
        )))
        .unwrap();

        assert_eq!(value["tags"], json!(["alpha", "zeta"]));
        assert!(value.get("notes").is_none());
    }

    #[test]
    fn bookmark_visibility_masks_private_and_hidden_bookmarks_from_non_owners() {
        let private = bookmark("alice", "private", "active", vec![]);
        assert!(bookmark_visible_to(&private, "alice"));
        assert!(!bookmark_visible_to(&private, "bob"));

        let public = bookmark("alice", "public", "active", vec![]);
        assert!(bookmark_visible_to(&public, "bob"));

        let hidden = bookmark("alice", "public", "hidden", vec![]);
        assert!(bookmark_visible_to(&hidden, "alice"));
        assert!(!bookmark_visible_to(&hidden, "bob"));
    }

    #[test]
    fn cursor_round_trips_created_at_and_id() {
        let cursor = Cursor {
            created_at: Utc.with_ymd_and_hms(2026, 7, 5, 12, 30, 0).unwrap(),
            id: Uuid::parse_str("00000000-0000-4000-8000-000000000001").unwrap(),
        };

        let raw = encode_cursor(Cursor {
            created_at: cursor.created_at,
            id: cursor.id,
        });
        let decoded = super::decode_cursor(&raw).unwrap();

        assert_eq!(decoded.created_at, cursor.created_at);
        assert_eq!(decoded.id, cursor.id);
        assert!(super::decode_cursor("not-base64").is_none());
    }

    #[test]
    fn decode_cursor_rejects_wrong_shape_and_invalid_uuid() {
        let missing_separator =
            base64::engine::general_purpose::URL_SAFE_NO_PAD.encode("2026-07-05T12:30:00Z");
        assert!(super::decode_cursor(&missing_separator).is_none());

        let invalid_uuid = base64::engine::general_purpose::URL_SAFE_NO_PAD
            .encode("2026-07-05T12:30:00Z|not-a-uuid");
        assert!(super::decode_cursor(&invalid_uuid).is_none());
    }

    #[test]
    fn parse_time_param_accepts_rfc3339_and_rejects_invalid_values() {
        let mut params = std::collections::HashMap::new();
        params.insert("from".to_string(), vec!["2026-07-05T10:15:00Z".to_string()]);
        params.insert("to".to_string(), vec!["not-a-time".to_string()]);

        assert!(parse_time_param(&params, "from").unwrap().is_some());
        assert_eq!(
            parse_time_param(&params, "to").unwrap_err(),
            "to must be an RFC 3339 timestamp"
        );
        assert_eq!(parse_time_param(&params, "missing").unwrap(), None);
    }

    #[test]
    fn validate_message_trims_key_and_language() {
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
        assert_eq!(message.description.as_deref(), Some("Navigation title"));
    }

    #[test]
    fn validate_message_collects_contract_field_errors() {
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
    fn validate_report_requires_known_reason_and_limits_comment() {
        let input = validate_report(&ReportRequest {
            reason: Some("spam".to_string()),
            comment: Some("looks automated".to_string()),
        })
        .unwrap();
        assert_eq!(input, "spam");

        let errors = validate_report(&ReportRequest {
            reason: Some("unknown".to_string()),
            comment: Some("x".repeat(1001)),
        })
        .unwrap_err();
        let keys = errors
            .into_iter()
            .map(|field| field.message_key)
            .collect::<Vec<_>>();
        assert!(keys.contains(&"validation.report.reason.invalid"));
        assert!(keys.contains(&"validation.report.comment.too-long"));
    }
}

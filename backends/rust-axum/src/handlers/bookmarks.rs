use std::sync::LazyLock;

use axum::Router;
use axum::extract::rejection::JsonRejection;
use axum::extract::{Extension, Json, Path, State};
use axum::http::{HeaderMap, StatusCode, Uri};
use axum::routing::get;
use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use chrono::{DateTime, SecondsFormat, Utc};
use regex::Regex;
use serde::{Deserialize, Serialize};
use sqlx::{AssertSqlSafe, FromRow, PgPool, Postgres, QueryBuilder, Row, Transaction};
use url::Url;
use uuid::Uuid;

use super::common::{
    HandlerResult, bad_request, db_result, json_body, require_identity, validation_result,
};
use super::wire::TagCount;
use crate::AppState;
use crate::auth::Identity;
use crate::error::{self, FieldViolation, Validator};

const BOOKMARK_COLUMNS: &str =
    "id, owner, url, title, notes, tags, visibility, status, created_at, updated_at";

static TAG_RE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^[a-z0-9-]{1,30}$").unwrap());

pub(super) fn routes() -> Router<AppState> {
    Router::new()
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
        .route("/api/v1/tags", get(list_tags))
}

#[derive(Debug, FromRow, Clone)]
pub(super) struct Bookmark {
    pub(super) id: Uuid,
    pub(super) owner: String,
    pub(super) url: String,
    pub(super) title: String,
    pub(super) notes: Option<String>,
    pub(super) tags: Vec<String>,
    pub(super) visibility: String,
    pub(super) status: String,
    pub(super) created_at: DateTime<Utc>,
    pub(super) updated_at: DateTime<Utc>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct BookmarkResponse {
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
pub(super) struct BookmarkRequest {
    pub(super) url: Option<String>,
    pub(super) title: Option<String>,
    pub(super) notes: Option<String>,
    #[serde(default)]
    pub(super) tags: Vec<String>,
    pub(super) visibility: Option<String>,
}

pub(super) struct ValidBookmark {
    pub(super) url: String,
    pub(super) title: String,
    pub(super) notes: Option<String>,
    pub(super) tags: Vec<String>,
    pub(super) visibility: String,
}

pub(super) fn validate_bookmark(
    body: BookmarkRequest,
) -> Result<ValidBookmark, Vec<FieldViolation>> {
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
pub(super) struct Cursor {
    pub(super) created_at: DateTime<Utc>,
    pub(super) id: Uuid,
}

pub(super) fn encode_cursor(cursor: Cursor) -> String {
    let raw = format!(
        "{}|{}",
        cursor
            .created_at
            .to_rfc3339_opts(SecondsFormat::AutoSi, true),
        cursor.id
    );
    URL_SAFE_NO_PAD.encode(raw)
}

pub(super) fn decode_cursor(raw: &str) -> Option<Cursor> {
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

pub(super) async fn bookmark_by_id(pool: &PgPool, id: Uuid) -> Result<Bookmark, sqlx::Error> {
    sqlx::query_as::<_, Bookmark>(AssertSqlSafe(format!(
        "select {BOOKMARK_COLUMNS} from bookmarks where id = $1"
    )))
    .bind(id)
    .fetch_one(pool)
    .await
}

pub(super) async fn bookmark_by_id_tx(
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

pub(super) fn bookmark_visible_to(bookmark: &Bookmark, caller: &str) -> bool {
    bookmark.owner == caller || (bookmark.visibility == "public" && bookmark.status == "active")
}

#[derive(Serialize)]
struct TagsResponse {
    tags: Vec<TagCount>,
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

#[cfg(test)]
#[path = "bookmarks_tests.rs"]
mod tests;

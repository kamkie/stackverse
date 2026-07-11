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
mod tests {
    use axum::Router;
    use axum::extract::Extension;
    use axum::http::{Method, StatusCode, header};
    use base64::Engine;
    use chrono::{TimeZone, Utc};
    use serde_json::{Value, json};
    use sqlx::PgPool;
    use tower::ServiceExt;
    use uuid::Uuid;

    use super::{
        Bookmark, BookmarkRequest, BookmarkResponse, Cursor, bookmark_visible_to, decode_cursor,
        encode_cursor, routes, validate_bookmark,
    };
    use crate::auth::Identity;
    use crate::test_support::{MIGRATOR, identity, json_body, json_request, request, state};

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

    fn app(pool: PgPool, caller: Identity) -> Router {
        routes().with_state(state(pool)).layer(Extension(caller))
    }

    async fn create(app: &Router, title: &str, tags: Value) -> Value {
        let response = app
            .clone()
            .oneshot(json_request(
                Method::POST,
                "/api/v1/bookmarks",
                json!({
                    "url": format!("https://example.com/{title}"),
                    "title": title,
                    "tags": tags,
                    "visibility": "public"
                }),
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::CREATED);
        assert!(response.headers().contains_key(header::LOCATION));
        json_body(response).await
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
        assert_eq!(bookmark.tags, vec!["rust", "web-dev"]);
        assert_eq!(bookmark.visibility, "private");
    }

    #[test]
    fn validate_bookmark_collects_field_errors() {
        let errors = match validate_bookmark(BookmarkRequest {
            url: Some("ftp://example.com".to_string()),
            title: Some(String::new()),
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
    fn cursors_round_trip_and_reject_malformed_values() {
        let cursor = Cursor {
            created_at: Utc.with_ymd_and_hms(2026, 7, 5, 12, 30, 0).unwrap(),
            id: Uuid::parse_str("00000000-0000-4000-8000-000000000001").unwrap(),
        };
        let raw = encode_cursor(Cursor {
            created_at: cursor.created_at,
            id: cursor.id,
        });
        let decoded = decode_cursor(&raw).unwrap();
        assert_eq!(decoded.created_at, cursor.created_at);
        assert_eq!(decoded.id, cursor.id);
        assert!(decode_cursor("not-base64").is_none());

        let missing_separator =
            base64::engine::general_purpose::URL_SAFE_NO_PAD.encode("2026-07-05T12:30:00Z");
        assert!(decode_cursor(&missing_separator).is_none());
        let invalid_timestamp = base64::engine::general_purpose::URL_SAFE_NO_PAD
            .encode("yesterday|00000000-0000-4000-8000-000000000001");
        assert!(decode_cursor(&invalid_timestamp).is_none());
        let invalid_uuid = base64::engine::general_purpose::URL_SAFE_NO_PAD
            .encode("2026-07-05T12:30:00Z|not-a-uuid");
        assert!(decode_cursor(&invalid_uuid).is_none());
    }

    #[sqlx::test(migrator = "MIGRATOR")]
    async fn bookmark_http_flow_covers_crud_visibility_tags_and_stable_cursors(pool: PgPool) {
        let alice_app = app(pool.clone(), identity("alice", &[]));
        let first = create(&alice_app, "first", json!(["Rust", "rust", "web"])).await;
        let first_id = Uuid::parse_str(first["id"].as_str().unwrap()).unwrap();
        sqlx::query("update bookmarks set created_at = '2026-07-01T10:00:00Z' where id = $1")
            .bind(first_id)
            .execute(&pool)
            .await
            .unwrap();

        let second = create(&alice_app, "second", json!(["rust"])).await;
        let second_id = Uuid::parse_str(second["id"].as_str().unwrap()).unwrap();
        sqlx::query("update bookmarks set created_at = '2026-07-02T10:00:00Z' where id = $1")
            .bind(second_id)
            .execute(&pool)
            .await
            .unwrap();

        let v1 = alice_app
            .clone()
            .oneshot(request(Method::GET, "/api/v1/bookmarks?page=0&size=10"))
            .await
            .unwrap();
        assert_eq!(v1.status(), StatusCode::OK);
        assert_eq!(v1.headers()["Deprecation"], "@1782864000");
        assert!(
            v1.headers()["Link"]
                .to_str()
                .unwrap()
                .contains("/api/v2/bookmarks")
        );
        let v1_body = json_body(v1).await;
        assert_eq!(v1_body["totalItems"], 2);
        assert_eq!(v1_body["items"][0]["id"], second["id"]);

        let page_one = alice_app
            .clone()
            .oneshot(request(Method::GET, "/api/v2/bookmarks?size=1"))
            .await
            .unwrap();
        let page_one = json_body(page_one).await;
        assert_eq!(page_one["items"][0]["id"], second["id"]);
        let cursor = page_one["nextCursor"].as_str().unwrap();

        let third = create(&alice_app, "third", json!(["new"])).await;
        let third_id = Uuid::parse_str(third["id"].as_str().unwrap()).unwrap();
        sqlx::query("update bookmarks set created_at = '2026-07-03T10:00:00Z' where id = $1")
            .bind(third_id)
            .execute(&pool)
            .await
            .unwrap();

        let page_two = alice_app
            .clone()
            .oneshot(request(
                Method::GET,
                &format!("/api/v2/bookmarks?size=1&cursor={cursor}"),
            ))
            .await
            .unwrap();
        let page_two = json_body(page_two).await;
        assert_eq!(page_two["items"][0]["id"], first["id"]);
        assert_ne!(page_two["items"][0]["id"], third["id"]);

        let tags = alice_app
            .clone()
            .oneshot(request(Method::GET, "/api/v1/tags"))
            .await
            .unwrap();
        let tags = json_body(tags).await;
        assert_eq!(tags["tags"][0], json!({"tag": "rust", "count": 2}));

        sqlx::query("update bookmarks set status = 'hidden' where id = $1")
            .bind(first_id)
            .execute(&pool)
            .await
            .unwrap();
        let conflict = alice_app
            .clone()
            .oneshot(json_request(
                Method::PUT,
                &format!("/api/v1/bookmarks/{first_id}"),
                json!({"url":"https://example.com/changed","title":"Changed","visibility":"public"}),
            ))
            .await
            .unwrap();
        assert_eq!(conflict.status(), StatusCode::CONFLICT);

        let updated = alice_app
            .clone()
            .oneshot(json_request(
                Method::PUT,
                &format!("/api/v1/bookmarks/{first_id}"),
                json!({"url":"https://example.com/changed","title":"Changed","visibility":"private"}),
            ))
            .await
            .unwrap();
        assert_eq!(updated.status(), StatusCode::OK);
        assert_eq!(json_body(updated).await["title"], "Changed");

        let bob_app = app(pool.clone(), identity("bob", &[]));
        let masked = bob_app
            .clone()
            .oneshot(request(
                Method::GET,
                &format!("/api/v1/bookmarks/{first_id}"),
            ))
            .await
            .unwrap();
        assert_eq!(masked.status(), StatusCode::NOT_FOUND);

        let deleted = alice_app
            .clone()
            .oneshot(request(
                Method::DELETE,
                &format!("/api/v1/bookmarks/{first_id}"),
            ))
            .await
            .unwrap();
        assert_eq!(deleted.status(), StatusCode::NO_CONTENT);

        let public_app = routes().with_state(state(pool));
        let public = public_app
            .oneshot(request(Method::GET, "/api/v1/bookmarks?visibility=public"))
            .await
            .unwrap();
        assert_eq!(public.status(), StatusCode::OK);
        assert_eq!(json_body(public).await["totalItems"], 2);
    }

    #[sqlx::test(migrator = "MIGRATOR")]
    async fn bookmark_http_boundaries_map_auth_validation_and_cursor_errors(pool: PgPool) {
        let anonymous = routes().with_state(state(pool.clone()));
        let unauthorized = anonymous
            .clone()
            .oneshot(request(Method::POST, "/api/v1/bookmarks"))
            .await
            .unwrap();
        assert_eq!(unauthorized.status(), StatusCode::UNAUTHORIZED);

        let invalid_visibility = anonymous
            .oneshot(request(Method::GET, "/api/v1/bookmarks?visibility=shared"))
            .await
            .unwrap();
        assert_eq!(invalid_visibility.status(), StatusCode::BAD_REQUEST);

        let alice = app(pool, identity("alice", &[]));
        let malformed_json = alice
            .clone()
            .oneshot(
                axum::http::Request::builder()
                    .method(Method::POST)
                    .uri("/api/v1/bookmarks")
                    .header(header::CONTENT_TYPE, "application/json")
                    .body(axum::body::Body::from("{"))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(malformed_json.status(), StatusCode::BAD_REQUEST);

        let invalid_fields = alice
            .clone()
            .oneshot(json_request(
                Method::POST,
                "/api/v1/bookmarks",
                json!({"url":"ftp://example.com","title":"","tags":["Bad Tag"]}),
            ))
            .await
            .unwrap();
        assert_eq!(invalid_fields.status(), StatusCode::BAD_REQUEST);
        let body = json_body(invalid_fields).await;
        assert!(
            body["errors"]
                .as_array()
                .unwrap()
                .iter()
                .any(|field| field["messageKey"] == "validation.url.invalid")
        );

        let malformed_cursor = alice
            .oneshot(request(
                Method::GET,
                "/api/v2/bookmarks?cursor=not-a-cursor",
            ))
            .await
            .unwrap();
        assert_eq!(malformed_cursor.status(), StatusCode::BAD_REQUEST);
    }
}

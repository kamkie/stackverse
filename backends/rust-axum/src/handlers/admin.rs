use std::collections::BTreeMap;

use axum::Router;
use axum::extract::rejection::JsonRejection;
use axum::extract::{Extension, Json, Path, State};
use axum::http::{HeaderMap, StatusCode, Uri};
use axum::routing::{get, put};
use chrono::{DateTime, Days, TimeZone, Utc};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sqlx::{AssertSqlSafe, FromRow, PgPool, Row};
use uuid::Uuid;

use super::common::{HandlerResult, audit_tx, bad_request, db_result, json_body, require_role};
use super::wire::TagCount;
use crate::AppState;
use crate::auth::Identity;
use crate::error::{self, Validator};

pub(super) fn routes() -> Router<AppState> {
    Router::new()
        .route("/api/v1/admin/users", get(list_users))
        .route("/api/v1/admin/users/{username}", get(get_user))
        .route(
            "/api/v1/admin/users/{username}/status",
            put(set_user_status),
        )
        .route("/api/v1/admin/audit-log", get(list_audit_log))
        .route("/api/v1/admin/stats", get(get_stats))
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

pub(super) fn parse_time_param(
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

#[cfg(test)]
mod tests {
    use std::collections::HashMap;

    use axum::Router;
    use axum::body::Body;
    use axum::extract::Extension;
    use axum::http::{Method, Request, StatusCode, header};
    use chrono::Utc;
    use serde_json::json;
    use sqlx::PgPool;
    use tower::ServiceExt;
    use uuid::Uuid;

    use super::{parse_time_param, routes};
    use crate::auth::Identity;
    use crate::test_support::{MIGRATOR, identity, json_body, json_request, request, state};

    fn app(pool: PgPool, caller: Identity) -> Router {
        routes().with_state(state(pool)).layer(Extension(caller))
    }

    async fn seed_accounts_and_bookmarks(pool: &PgPool) -> (Uuid, Uuid) {
        let now = Utc::now();
        for username in ["admin", "alice", "bob"] {
            sqlx::query(
                "insert into user_accounts (username, first_seen, last_seen, status) values ($1, $2, $2, 'active')",
            )
            .bind(username)
            .bind(now)
            .execute(pool)
            .await
            .unwrap();
        }
        let public_id = Uuid::new_v4();
        let hidden_id = Uuid::new_v4();
        sqlx::query(
            "insert into bookmarks (id, owner, url, title, tags, visibility, status, created_at, updated_at) values ($1, 'alice', 'https://example.com/public', 'Public', $2, 'public', 'active', $3, $3), ($4, 'alice', 'https://example.com/hidden', 'Hidden', $5, 'private', 'hidden', $3, $3)",
        )
        .bind(public_id)
        .bind(vec!["rust".to_string(), "web".to_string()])
        .bind(now)
        .bind(hidden_id)
        .bind(vec!["rust".to_string()])
        .execute(pool)
        .await
        .unwrap();
        sqlx::query(
            "insert into reports (id, bookmark_id, reporter, reason, status, created_at) values ($1, $2, 'bob', 'spam', 'open', $3)",
        )
        .bind(Uuid::new_v4())
        .bind(public_id)
        .bind(now)
        .execute(pool)
        .await
        .unwrap();
        (public_id, hidden_id)
    }

    #[test]
    fn parse_time_param_accepts_rfc3339_and_rejects_invalid_values() {
        let mut params = HashMap::new();
        params.insert("from".to_string(), vec!["2026-07-05T10:15:00Z".to_string()]);
        params.insert("to".to_string(), vec!["not-a-time".to_string()]);

        assert!(parse_time_param(&params, "from").unwrap().is_some());
        assert_eq!(
            parse_time_param(&params, "to").unwrap_err(),
            "to must be an RFC 3339 timestamp"
        );
        assert_eq!(parse_time_param(&params, "missing").unwrap(), None);
    }

    #[sqlx::test(migrator = "MIGRATOR")]
    async fn admin_http_flow_covers_accounts_audit_stats_and_etags(pool: PgPool) {
        seed_accounts_and_bookmarks(&pool).await;
        let admin = app(pool.clone(), identity("admin", &["admin", "moderator"]));

        let users = admin
            .clone()
            .oneshot(request(
                Method::GET,
                "/api/v1/admin/users?q=ALI&status=active&size=10",
            ))
            .await
            .unwrap();
        assert_eq!(users.status(), StatusCode::OK);
        let users = json_body(users).await;
        assert_eq!(users["totalItems"], 1);
        assert_eq!(users["items"][0]["bookmarkCount"], 2);

        let account = admin
            .clone()
            .oneshot(request(Method::GET, "/api/v1/admin/users/alice"))
            .await
            .unwrap();
        assert_eq!(json_body(account).await["username"], "alice");

        let blocked = admin
            .clone()
            .oneshot(json_request(
                Method::PUT,
                "/api/v1/admin/users/alice/status",
                json!({"status":"blocked","reason":"Repeated abuse"}),
            ))
            .await
            .unwrap();
        assert_eq!(blocked.status(), StatusCode::OK);
        let blocked = json_body(blocked).await;
        assert_eq!(blocked["status"], "blocked");
        assert_eq!(blocked["blockedReason"], "Repeated abuse");

        let audit = admin
            .clone()
            .oneshot(request(
                Method::GET,
                "/api/v1/admin/audit-log?action=user.blocked&targetType=user&targetId=alice",
            ))
            .await
            .unwrap();
        let audit = json_body(audit).await;
        assert_eq!(audit["totalItems"], 1);
        assert_eq!(audit["items"][0]["actor"], "admin");

        let unblocked = admin
            .clone()
            .oneshot(json_request(
                Method::PUT,
                "/api/v1/admin/users/alice/status",
                json!({"status":"active","reason":"ignored"}),
            ))
            .await
            .unwrap();
        assert_eq!(json_body(unblocked).await["status"], "active");

        let self_block = admin
            .clone()
            .oneshot(json_request(
                Method::PUT,
                "/api/v1/admin/users/admin/status",
                json!({"status":"blocked","reason":"No"}),
            ))
            .await
            .unwrap();
        assert_eq!(self_block.status(), StatusCode::CONFLICT);

        let stats = admin
            .clone()
            .oneshot(request(Method::GET, "/api/v1/admin/stats"))
            .await
            .unwrap();
        assert_eq!(stats.status(), StatusCode::OK);
        let stats_etag = stats.headers()[header::ETAG].to_str().unwrap().to_string();
        let stats_body = json_body(stats).await;
        assert_eq!(stats_body["totals"]["users"], 3);
        assert_eq!(stats_body["totals"]["bookmarks"], 2);
        assert_eq!(stats_body["totals"]["hiddenBookmarks"], 1);
        assert_eq!(stats_body["totals"]["openReports"], 1);
        assert_eq!(stats_body["daily"].as_array().unwrap().len(), 30);
        assert_eq!(stats_body["topTags"][0], json!({"tag":"rust","count":2}));

        let cached = admin
            .oneshot(
                Request::builder()
                    .uri("/api/v1/admin/stats")
                    .header(header::IF_NONE_MATCH, stats_etag)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(cached.status(), StatusCode::NOT_MODIFIED);
    }

    #[sqlx::test(migrator = "MIGRATOR")]
    async fn admin_http_boundaries_enforce_roles_and_validation(pool: PgPool) {
        seed_accounts_and_bookmarks(&pool).await;
        let anonymous = routes().with_state(state(pool.clone()));
        let unauthorized = anonymous
            .oneshot(request(Method::GET, "/api/v1/admin/users"))
            .await
            .unwrap();
        assert_eq!(unauthorized.status(), StatusCode::UNAUTHORIZED);

        let regular = app(pool.clone(), identity("alice", &[]));
        let forbidden = regular
            .oneshot(request(Method::GET, "/api/v1/admin/users"))
            .await
            .unwrap();
        assert_eq!(forbidden.status(), StatusCode::FORBIDDEN);

        let admin = app(pool, identity("admin", &["admin", "moderator"]));
        for uri in [
            "/api/v1/admin/users?size=0",
            "/api/v1/admin/users?status=unknown",
            "/api/v1/admin/audit-log?from=yesterday",
        ] {
            let response = admin
                .clone()
                .oneshot(request(Method::GET, uri))
                .await
                .unwrap();
            assert_eq!(response.status(), StatusCode::BAD_REQUEST, "{uri}");
        }

        let missing_reason = admin
            .clone()
            .oneshot(json_request(
                Method::PUT,
                "/api/v1/admin/users/bob/status",
                json!({"status":"blocked"}),
            ))
            .await
            .unwrap();
        assert_eq!(missing_reason.status(), StatusCode::BAD_REQUEST);

        let invalid_status = admin
            .clone()
            .oneshot(json_request(
                Method::PUT,
                "/api/v1/admin/users/bob/status",
                json!({"status":"suspended","reason":"No"}),
            ))
            .await
            .unwrap();
        assert_eq!(invalid_status.status(), StatusCode::BAD_REQUEST);

        let missing = admin
            .oneshot(request(Method::GET, "/api/v1/admin/users/missing"))
            .await
            .unwrap();
        assert_eq!(missing.status(), StatusCode::NOT_FOUND);
    }
}

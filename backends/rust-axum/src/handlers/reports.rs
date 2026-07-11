use super::bookmarks::{BookmarkResponse, bookmark_by_id, bookmark_by_id_tx};
use axum::Router;
use axum::extract::rejection::JsonRejection;
use axum::extract::{Extension, Json, Path, State};
use axum::http::{HeaderMap, StatusCode, Uri};
use axum::routing::{get, post, put};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use serde_json::json;
use sqlx::{AssertSqlSafe, FromRow, Postgres, Transaction};
use uuid::Uuid;

use super::common::{
    HandlerResult, audit_tx, bad_request, db_error, db_result, json_body, require_identity,
    require_role, validation_result,
};
use crate::AppState;
use crate::auth::Identity;
use crate::db;
use crate::error::{self, FieldViolation, Validator};

const REPORT_COLUMNS: &str = "id, bookmark_id, reporter, reason, comment, status, resolved_by, resolved_at, resolution_note, created_at";

pub(super) fn routes() -> Router<AppState> {
    Router::new()
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
pub(super) struct ReportRequest {
    pub(super) reason: Option<String>,
    pub(super) comment: Option<String>,
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

pub(super) fn validate_report(body: &ReportRequest) -> Result<String, Vec<FieldViolation>> {
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

#[cfg(test)]
mod tests {
    use axum::Router;
    use axum::extract::Extension;
    use axum::http::{Method, StatusCode};
    use chrono::{TimeZone, Utc};
    use serde_json::{Value, json};
    use sqlx::PgPool;
    use tower::ServiceExt;
    use uuid::Uuid;

    use super::{ReportRequest, ReportResponse, ReportRow, routes, validate_report};
    use crate::auth::Identity;
    use crate::test_support::{MIGRATOR, identity, json_body, json_request, request, state};

    fn app(pool: PgPool, caller: Identity) -> Router {
        routes().with_state(state(pool)).layer(Extension(caller))
    }

    async fn seed_bookmark(pool: &PgPool, visibility: &str, status: &str) -> Uuid {
        let id = Uuid::new_v4();
        let now = Utc::now();
        sqlx::query(
            "insert into bookmarks (id, owner, url, title, tags, visibility, status, created_at, updated_at) values ($1, 'alice', 'https://example.com/reported', 'Reported', $2, $3, $4, $5, $5)",
        )
        .bind(id)
        .bind(vec!["rust".to_string()])
        .bind(visibility)
        .bind(status)
        .bind(now)
        .execute(pool)
        .await
        .unwrap();
        id
    }

    async fn report(app: &Router, bookmark_id: Uuid, reason: &str) -> Value {
        let response = app
            .clone()
            .oneshot(json_request(
                Method::POST,
                &format!("/api/v1/bookmarks/{bookmark_id}/reports"),
                json!({"reason":reason,"comment":"Please review"}),
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::CREATED);
        json_body(response).await
    }

    #[test]
    fn report_validation_and_response_follow_the_wire_contract() {
        assert_eq!(
            validate_report(&ReportRequest {
                reason: Some("broken-link".to_string()),
                comment: Some("Gone".to_string()),
            })
            .unwrap(),
            "broken-link"
        );
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

        let now = Utc.with_ymd_and_hms(2026, 7, 5, 12, 0, 0).unwrap();
        let value = serde_json::to_value(ReportResponse::from(ReportRow {
            id: Uuid::nil(),
            bookmark_id: Uuid::new_v4(),
            reporter: "alice".to_string(),
            reason: "spam".to_string(),
            comment: None,
            status: "open".to_string(),
            resolved_by: None,
            resolved_at: None,
            resolution_note: None,
            created_at: now,
        }))
        .unwrap();
        assert!(value.get("comment").is_none());
        assert!(value.get("resolvedBy").is_none());
    }

    #[sqlx::test(migrator = "MIGRATOR")]
    async fn report_http_flow_covers_reporter_and_moderation_state_transitions(pool: PgPool) {
        let bookmark_id = seed_bookmark(&pool, "public", "active").await;
        let bob = app(pool.clone(), identity("bob", &[]));
        let moderator = app(pool.clone(), identity("moderator", &["moderator"]));

        let first = report(&bob, bookmark_id, "spam").await;
        let first_id = Uuid::parse_str(first["id"].as_str().unwrap()).unwrap();
        let duplicate = bob
            .clone()
            .oneshot(json_request(
                Method::POST,
                &format!("/api/v1/bookmarks/{bookmark_id}/reports"),
                json!({"reason":"other"}),
            ))
            .await
            .unwrap();
        assert_eq!(duplicate.status(), StatusCode::CONFLICT);

        let mine = bob
            .clone()
            .oneshot(request(Method::GET, "/api/v1/reports?status=open"))
            .await
            .unwrap();
        assert_eq!(json_body(mine).await["totalItems"], 1);

        let updated = bob
            .clone()
            .oneshot(json_request(
                Method::PUT,
                &format!("/api/v1/reports/{first_id}"),
                json!({"reason":"broken-link","comment":"Now gone"}),
            ))
            .await
            .unwrap();
        assert_eq!(updated.status(), StatusCode::OK);
        assert_eq!(json_body(updated).await["reason"], "broken-link");

        let queue = moderator
            .clone()
            .oneshot(request(Method::GET, "/api/v1/admin/reports"))
            .await
            .unwrap();
        assert_eq!(json_body(queue).await["totalItems"], 1);

        let dismissed = moderator
            .clone()
            .oneshot(json_request(
                Method::PUT,
                &format!("/api/v1/admin/reports/{first_id}"),
                json!({"resolution":"dismissed","note":"Not actionable"}),
            ))
            .await
            .unwrap();
        assert_eq!(json_body(dismissed).await["status"], "dismissed");

        let second = report(&bob, bookmark_id, "offensive").await;
        let second_id = Uuid::parse_str(second["id"].as_str().unwrap()).unwrap();
        let duplicate_reopen = moderator
            .clone()
            .oneshot(json_request(
                Method::PUT,
                &format!("/api/v1/admin/reports/{first_id}"),
                json!({"resolution":"open","note":"ignored"}),
            ))
            .await
            .unwrap();
        assert_eq!(duplicate_reopen.status(), StatusCode::CONFLICT);

        let withdrawn = bob
            .clone()
            .oneshot(request(
                Method::DELETE,
                &format!("/api/v1/reports/{second_id}"),
            ))
            .await
            .unwrap();
        assert_eq!(withdrawn.status(), StatusCode::NO_CONTENT);

        let reopened = moderator
            .clone()
            .oneshot(json_request(
                Method::PUT,
                &format!("/api/v1/admin/reports/{first_id}"),
                json!({"resolution":"open","note":"ignored"}),
            ))
            .await
            .unwrap();
        let reopened = json_body(reopened).await;
        assert_eq!(reopened["status"], "open");
        assert!(reopened.get("resolvedBy").is_none());

        let charlie = app(pool.clone(), identity("charlie", &[]));
        let sibling = report(&charlie, bookmark_id, "other").await;
        let sibling_id = Uuid::parse_str(sibling["id"].as_str().unwrap()).unwrap();

        let actioned = moderator
            .clone()
            .oneshot(json_request(
                Method::PUT,
                &format!("/api/v1/admin/reports/{first_id}"),
                json!({"resolution":"actioned","note":"Confirmed"}),
            ))
            .await
            .unwrap();
        assert_eq!(json_body(actioned).await["status"], "actioned");
        let sibling_status: String = sqlx::query_scalar("select status from reports where id = $1")
            .bind(sibling_id)
            .fetch_one(&pool)
            .await
            .unwrap();
        assert_eq!(sibling_status, "actioned");
        let bookmark_status: String =
            sqlx::query_scalar("select status from bookmarks where id = $1")
                .bind(bookmark_id)
                .fetch_one(&pool)
                .await
                .unwrap();
        assert_eq!(bookmark_status, "hidden");

        let resolved_update = bob
            .clone()
            .oneshot(json_request(
                Method::PUT,
                &format!("/api/v1/reports/{first_id}"),
                json!({"reason":"spam"}),
            ))
            .await
            .unwrap();
        assert_eq!(resolved_update.status(), StatusCode::CONFLICT);

        let hidden_report = app(pool.clone(), identity("dana", &[]))
            .oneshot(json_request(
                Method::POST,
                &format!("/api/v1/bookmarks/{bookmark_id}/reports"),
                json!({"reason":"spam"}),
            ))
            .await
            .unwrap();
        assert_eq!(hidden_report.status(), StatusCode::NOT_FOUND);

        let restored = moderator
            .clone()
            .oneshot(json_request(
                Method::PUT,
                &format!("/api/v1/admin/bookmarks/{bookmark_id}/status"),
                json!({"status":"active","note":"Reviewed"}),
            ))
            .await
            .unwrap();
        assert_eq!(json_body(restored).await["status"], "active");

        let report_audits: i64 = sqlx::query_scalar(
            "select count(*) from audit_entries where action in ('report.resolved', 'report.reopened', 'bookmark.status-changed')",
        )
        .fetch_one(&pool)
        .await
        .unwrap();
        assert!(report_audits >= 6);
    }

    #[sqlx::test(migrator = "MIGRATOR")]
    async fn report_http_boundaries_enforce_auth_roles_and_validation(pool: PgPool) {
        let bookmark_id = seed_bookmark(&pool, "public", "active").await;
        let anonymous = routes().with_state(state(pool.clone()));
        let unauthorized = anonymous
            .oneshot(json_request(
                Method::POST,
                &format!("/api/v1/bookmarks/{bookmark_id}/reports"),
                json!({"reason":"spam"}),
            ))
            .await
            .unwrap();
        assert_eq!(unauthorized.status(), StatusCode::UNAUTHORIZED);

        let regular = app(pool.clone(), identity("bob", &[]));
        let invalid_report = regular
            .clone()
            .oneshot(json_request(
                Method::POST,
                &format!("/api/v1/bookmarks/{bookmark_id}/reports"),
                json!({"reason":"unknown","comment":"x".repeat(1001)}),
            ))
            .await
            .unwrap();
        assert_eq!(invalid_report.status(), StatusCode::BAD_REQUEST);

        let invalid_filter = regular
            .clone()
            .oneshot(request(Method::GET, "/api/v1/reports?status=unknown"))
            .await
            .unwrap();
        assert_eq!(invalid_filter.status(), StatusCode::BAD_REQUEST);

        let forbidden = regular
            .oneshot(request(Method::GET, "/api/v1/admin/reports"))
            .await
            .unwrap();
        assert_eq!(forbidden.status(), StatusCode::FORBIDDEN);

        let moderator = app(pool, identity("moderator", &["moderator"]));
        let invalid_resolution = moderator
            .clone()
            .oneshot(json_request(
                Method::PUT,
                "/api/v1/admin/reports/00000000-0000-4000-8000-000000000001",
                json!({"resolution":"pending"}),
            ))
            .await
            .unwrap();
        assert_eq!(invalid_resolution.status(), StatusCode::BAD_REQUEST);

        let invalid_bookmark_status = moderator
            .oneshot(json_request(
                Method::PUT,
                &format!("/api/v1/admin/bookmarks/{bookmark_id}/status"),
                json!({"status":"archived"}),
            ))
            .await
            .unwrap();
        assert_eq!(invalid_bookmark_status.status(), StatusCode::BAD_REQUEST);
    }
}

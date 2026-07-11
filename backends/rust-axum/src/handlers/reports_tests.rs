use axum::Router;
use axum::http::{Method, StatusCode};
use chrono::{TimeZone, Utc};
use serde_json::{Value, json};
use sqlx::PgPool;
use tower::ServiceExt;
use uuid::Uuid;

use super::{ReportRequest, ReportResponse, ReportRow, routes, validate_report};
use crate::test_support::{
    MIGRATOR, anonymous_app, app, identity, json_body, json_request, request,
};

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
    let bob = app(routes(), pool.clone(), identity("bob", &[]));
    let moderator = app(
        routes(),
        pool.clone(),
        identity("moderator", &["moderator"]),
    );

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

    let charlie = app(routes(), pool.clone(), identity("charlie", &[]));
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
    let bookmark_status: String = sqlx::query_scalar("select status from bookmarks where id = $1")
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

    let hidden_report = app(routes(), pool.clone(), identity("dana", &[]))
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
    let anonymous = anonymous_app(routes(), pool.clone());
    let unauthorized = anonymous
        .oneshot(json_request(
            Method::POST,
            &format!("/api/v1/bookmarks/{bookmark_id}/reports"),
            json!({"reason":"spam"}),
        ))
        .await
        .unwrap();
    assert_eq!(unauthorized.status(), StatusCode::UNAUTHORIZED);

    let regular = app(routes(), pool.clone(), identity("bob", &[]));
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

    let moderator = app(routes(), pool, identity("moderator", &["moderator"]));
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

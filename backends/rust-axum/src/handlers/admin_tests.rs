use std::collections::HashMap;

use axum::body::Body;
use axum::http::{Method, Request, StatusCode, header};
use chrono::Utc;
use serde_json::json;
use sqlx::PgPool;
use tower::ServiceExt;
use uuid::Uuid;

use super::{parse_time_param, routes};
use crate::test_support::{
    MIGRATOR, anonymous_app, app, identity, json_body, json_request, request,
};

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
    let admin = app(
        routes(),
        pool.clone(),
        identity("admin", &["admin", "moderator"]),
    );

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
    let anonymous = anonymous_app(routes(), pool.clone());
    let unauthorized = anonymous
        .oneshot(request(Method::GET, "/api/v1/admin/users"))
        .await
        .unwrap();
    assert_eq!(unauthorized.status(), StatusCode::UNAUTHORIZED);

    let regular = app(routes(), pool.clone(), identity("alice", &[]));
    let forbidden = regular
        .oneshot(request(Method::GET, "/api/v1/admin/users"))
        .await
        .unwrap();
    assert_eq!(forbidden.status(), StatusCode::FORBIDDEN);

    let admin = app(routes(), pool, identity("admin", &["admin", "moderator"]));
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

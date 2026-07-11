use axum::Router;
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
use crate::test_support::{
    MIGRATOR, anonymous_app, app, identity, json_body, json_request, request,
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
    let created_at = Utc.with_ymd_and_hms(2026, 7, 5, 12, 30, 0).unwrap();
    let id = Uuid::parse_str("00000000-0000-4000-8000-000000000001").unwrap();
    let raw = encode_cursor(Cursor { created_at, id });
    let decoded = decode_cursor(&raw).unwrap();
    assert_eq!(decoded.created_at, created_at);
    assert_eq!(decoded.id, id);
    assert!(decode_cursor("not-base64").is_none());

    let missing_separator =
        base64::engine::general_purpose::URL_SAFE_NO_PAD.encode("2026-07-05T12:30:00Z");
    assert!(decode_cursor(&missing_separator).is_none());
    let invalid_timestamp = base64::engine::general_purpose::URL_SAFE_NO_PAD
        .encode("yesterday|00000000-0000-4000-8000-000000000001");
    assert!(decode_cursor(&invalid_timestamp).is_none());
    let invalid_uuid =
        base64::engine::general_purpose::URL_SAFE_NO_PAD.encode("2026-07-05T12:30:00Z|not-a-uuid");
    assert!(decode_cursor(&invalid_uuid).is_none());
}

#[sqlx::test(migrator = "MIGRATOR")]
async fn bookmark_http_flow_covers_crud_visibility_tags_and_stable_cursors(pool: PgPool) {
    let alice_app = app(routes(), pool.clone(), identity("alice", &[]));
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

    let bob_app = app(routes(), pool.clone(), identity("bob", &[]));
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

    let public_app = anonymous_app(routes(), pool);
    let public = public_app
        .oneshot(request(Method::GET, "/api/v1/bookmarks?visibility=public"))
        .await
        .unwrap();
    assert_eq!(public.status(), StatusCode::OK);
    assert_eq!(json_body(public).await["totalItems"], 2);
}

#[sqlx::test(migrator = "MIGRATOR")]
async fn bookmark_http_boundaries_map_auth_validation_and_cursor_errors(pool: PgPool) {
    let anonymous = anonymous_app(routes(), pool.clone());
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

    let alice = app(routes(), pool, identity("alice", &[]));
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

use axum::Router;
use axum::body::Body;
use axum::http::{Method, Request, StatusCode, header};
use chrono::{TimeZone, Utc};
use serde_json::{Value, json};
use sqlx::PgPool;
use tower::ServiceExt;
use uuid::Uuid;

use super::{Message, MessageRequest, MessageResponse, routes, validate_message};
use crate::test_support::{
    MIGRATOR, anonymous_app, app, identity, json_body, json_request, request,
};

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
    assert_eq!(message.description.as_deref(), Some("Navigation title"));

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
    let admin = app(
        routes(),
        pool.clone(),
        identity("admin", &["admin", "moderator"]),
    );
    let english = create(&admin, "ui.greeting", "en", "Hello").await;
    let polish = create(&admin, "ui.greeting", "pl", "Cześć").await;
    assert_eq!(english["description"], "Shown on the home page");
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
    let anonymous = anonymous_app(routes(), pool.clone());
    let missing_identity = anonymous
        .oneshot(request(Method::POST, "/api/v1/messages"))
        .await
        .unwrap();
    assert_eq!(missing_identity.status(), StatusCode::UNAUTHORIZED);

    let regular = app(routes(), pool.clone(), identity("alice", &[]));
    let forbidden = regular
        .oneshot(request(Method::POST, "/api/v1/messages"))
        .await
        .unwrap();
    assert_eq!(forbidden.status(), StatusCode::FORBIDDEN);

    let admin = app(routes(), pool, identity("admin", &["admin"]));
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

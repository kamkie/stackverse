use base64::Engine;
use chrono::{TimeZone, Utc};
use serde_json::json;
use uuid::Uuid;

use super::admin::parse_time_param;
use super::bookmarks::{
    Bookmark, BookmarkRequest, BookmarkResponse, Cursor, bookmark_visible_to, encode_cursor,
    validate_bookmark,
};
use super::messages::{MessageRequest, validate_message};
use super::reports::{ReportRequest, validate_report};

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
    let decoded = super::bookmarks::decode_cursor(&raw).unwrap();

    assert_eq!(decoded.created_at, cursor.created_at);
    assert_eq!(decoded.id, cursor.id);
    assert!(super::bookmarks::decode_cursor("not-base64").is_none());
}

#[test]
fn decode_cursor_rejects_wrong_shape_and_invalid_uuid() {
    let missing_separator =
        base64::engine::general_purpose::URL_SAFE_NO_PAD.encode("2026-07-05T12:30:00Z");
    assert!(super::bookmarks::decode_cursor(&missing_separator).is_none());

    let invalid_uuid =
        base64::engine::general_purpose::URL_SAFE_NO_PAD.encode("2026-07-05T12:30:00Z|not-a-uuid");
    assert!(super::bookmarks::decode_cursor(&invalid_uuid).is_none());
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

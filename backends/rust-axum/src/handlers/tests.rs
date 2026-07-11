use axum::extract::Extension;
use axum::http::{HeaderMap, Method, StatusCode, Uri, header};
use axum::response::IntoResponse;
use tower::ServiceExt;

use super::common::{db_result, require_identity, require_role, validation_result};
use crate::error::FieldViolation;
use crate::test_support::{MIGRATOR, identity, json_body, request, state};

#[sqlx::test(migrator = "MIGRATOR")]
async fn aggregate_router_covers_health_readiness_fallback_and_authentication(pool: sqlx::PgPool) {
    let app = crate::app(state(pool));

    let health = app
        .clone()
        .oneshot(request(Method::GET, "/healthz"))
        .await
        .unwrap();
    assert_eq!(health.status(), StatusCode::OK);

    let readiness = app
        .clone()
        .oneshot(request(Method::GET, "/readyz"))
        .await
        .unwrap();
    assert_eq!(readiness.status(), StatusCode::OK);

    let missing = app
        .clone()
        .oneshot(request(Method::GET, "/missing"))
        .await
        .unwrap();
    assert_eq!(missing.status(), StatusCode::NOT_FOUND);
    assert_eq!(
        missing.headers()[header::CONTENT_TYPE],
        "application/problem+json"
    );

    let unauthenticated = app
        .clone()
        .oneshot(request(Method::GET, "/api/v1/me"))
        .await
        .unwrap();
    assert_eq!(unauthenticated.status(), StatusCode::UNAUTHORIZED);

    let mut invalid_token = request(Method::GET, "/api/v1/me");
    invalid_token
        .headers_mut()
        .insert(header::AUTHORIZATION, "Bearer not-a-jwt".parse().unwrap());
    let invalid_token = app.oneshot(invalid_token).await.unwrap();
    assert_eq!(invalid_token.status(), StatusCode::UNAUTHORIZED);
    assert_eq!(
        json_body(invalid_token).await["detail"],
        "Missing or invalid bearer token."
    );
}

#[sqlx::test(migrator = "MIGRATOR")]
async fn identity_route_and_common_boundaries_map_success_and_failures(pool: sqlx::PgPool) {
    let state = state(pool);
    let uri: Uri = "/api/v1/me".parse().unwrap();
    let headers = HeaderMap::new();
    let admin = identity("admin", &["admin", "moderator"]);

    let required = require_identity(&state, &headers, &uri, Some(Extension(admin.clone())))
        .await
        .unwrap();
    assert_eq!(required.username, "admin");
    assert!(
        require_identity(&state, &headers, &uri, None)
            .await
            .is_err()
    );
    assert!(
        require_role(
            &state,
            &headers,
            &uri,
            Some(Extension(admin.clone())),
            "moderator"
        )
        .await
        .is_ok()
    );
    assert!(
        require_role(
            &state,
            &headers,
            &uri,
            Some(Extension(identity("alice", &[]))),
            "admin"
        )
        .await
        .is_err()
    );

    let identity_app = super::identity::routes()
        .with_state(state.clone())
        .layer(Extension(admin));
    let me = identity_app
        .oneshot(request(Method::GET, "/api/v1/me"))
        .await
        .unwrap();
    assert_eq!(me.status(), StatusCode::OK);
    let me = json_body(me).await;
    assert_eq!(me["username"], "admin");
    assert_eq!(me["name"], "admin Example");
    assert_eq!(me["roles"], serde_json::json!(["admin", "moderator"]));

    let ok = validation_result(&state, &headers, &uri, Ok::<_, Vec<FieldViolation>>(42))
        .await
        .unwrap();
    assert_eq!(ok, 42);
    let invalid = validation_result::<()>(
        &state,
        &headers,
        &uri,
        Err(vec![FieldViolation {
            field: "title",
            message_key: "validation.title.required",
        }]),
    )
    .await
    .unwrap_err()
    .into_response();
    assert_eq!(invalid.status(), StatusCode::BAD_REQUEST);

    let not_found = db_result::<()>(&state, &headers, &uri, Err(sqlx::Error::RowNotFound))
        .unwrap_err()
        .into_response();
    assert_eq!(not_found.status(), StatusCode::NOT_FOUND);
    let internal = db_result::<()>(
        &state,
        &headers,
        &uri,
        Err(sqlx::Error::Protocol("test database failure".to_string())),
    )
    .unwrap_err()
    .into_response();
    assert_eq!(internal.status(), StatusCode::INTERNAL_SERVER_ERROR);
}

use axum::Json;
use axum::http::{StatusCode, header};
use axum::response::{IntoResponse, Response};
use serde::Serialize;

#[derive(Serialize)]
struct Problem<'a> {
    #[serde(rename = "type")]
    type_: &'a str,
    title: &'a str,
    status: u16,
    detail: &'a str,
}

pub fn problem(status: StatusCode, title: &'static str, detail: &'static str) -> Response {
    let mut response = (
        status,
        Json(Problem {
            type_: "about:blank",
            title,
            status: status.as_u16(),
            detail,
        }),
    )
        .into_response();
    response.headers_mut().insert(
        header::CONTENT_TYPE,
        "application/problem+json"
            .parse()
            .expect("valid content type"),
    );
    response
}

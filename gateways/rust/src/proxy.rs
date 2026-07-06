use axum::body::Body;
use axum::http::{Request, Response, StatusCode};
use futures_util::TryStreamExt;
use opentelemetry::global;
use opentelemetry::propagation::Injector;
use tracing_opentelemetry::OpenTelemetrySpanExt;
use url::Url;

use crate::AppState;
use crate::problems;

pub async fn proxy(
    state: &AppState,
    request: Request<Body>,
    target: &Url,
    dependency: &'static str,
    api: bool,
    access_token: Option<&str>,
) -> Response<Body> {
    let method = request.method().clone();
    let uri = request.uri().clone();
    let upstream_url = upstream_url(target, uri.path(), uri.query());
    let (parts, body) = request.into_parts();

    let mut headers = reqwest::header::HeaderMap::new();
    for (name, value) in parts.headers.iter() {
        if skip_request_header(name.as_str(), api) {
            continue;
        }
        headers.insert(name, value.clone());
    }
    if api {
        if let Some(token) = access_token {
            if let Ok(value) = reqwest::header::HeaderValue::from_str(&format!("Bearer {token}")) {
                headers.insert(reqwest::header::AUTHORIZATION, value);
            }
        }
    }
    inject_trace_context(&mut headers);

    let body = reqwest::Body::wrap_stream(body.into_data_stream());
    let method =
        reqwest::Method::from_bytes(method.as_str().as_bytes()).unwrap_or(reqwest::Method::GET);
    let response = state
        .http
        .request(method, upstream_url)
        .headers(headers)
        .body(body)
        .send()
        .await;

    match response {
        Ok(response) => upstream_response(response),
        Err(err) => {
            tracing::error!(
                event = "dependency_call_failed",
                outcome = "failure",
                dependency,
                error_code = %format!("{dependency}_unavailable"),
                error = %err,
                "Gateway upstream call failed"
            );
            if api {
                problems::problem(
                    StatusCode::BAD_GATEWAY,
                    "Bad Gateway",
                    "The upstream API is temporarily unavailable.",
                )
            } else {
                Response::builder()
                    .status(StatusCode::BAD_GATEWAY)
                    .header("content-type", "text/plain; charset=utf-8")
                    .body(Body::from("Bad Gateway"))
                    .expect("valid response")
            }
        }
    }
}

fn upstream_url(target: &Url, path: &str, query: Option<&str>) -> Url {
    let mut url = target.clone();
    url.set_path(path);
    url.set_query(query);
    url
}

fn upstream_response(response: reqwest::Response) -> Response<Body> {
    let status =
        StatusCode::from_u16(response.status().as_u16()).unwrap_or(StatusCode::BAD_GATEWAY);
    let mut builder = Response::builder().status(status);
    for (name, value) in response.headers() {
        if skip_response_header(name.as_str()) {
            continue;
        }
        builder = builder.header(name, value);
    }
    let stream = response
        .bytes_stream()
        .map_err(|err| std::io::Error::new(std::io::ErrorKind::Other, err));
    builder
        .body(Body::from_stream(stream))
        .expect("valid upstream response")
}

fn skip_request_header(name: &str, api: bool) -> bool {
    let lower = name.to_ascii_lowercase();
    if hop_by_hop(&lower) || lower == "host" || lower == "content-length" {
        return true;
    }
    if api {
        lower == "cookie" || lower == "authorization" || lower == "x-xsrf-token"
    } else {
        lower == "cookie"
    }
}

fn skip_response_header(name: &str) -> bool {
    let lower = name.to_ascii_lowercase();
    hop_by_hop(&lower)
}

fn hop_by_hop(name: &str) -> bool {
    matches!(
        name,
        "connection"
            | "keep-alive"
            | "proxy-authenticate"
            | "proxy-authorization"
            | "te"
            | "trailer"
            | "transfer-encoding"
            | "upgrade"
    )
}

fn inject_trace_context(headers: &mut reqwest::header::HeaderMap) {
    let context = tracing::Span::current().context();
    global::get_text_map_propagator(|propagator| {
        propagator.inject_context(&context, &mut HeaderInjector(headers))
    });
}

struct HeaderInjector<'a>(&'a mut reqwest::header::HeaderMap);

impl Injector for HeaderInjector<'_> {
    fn set(&mut self, key: &str, value: String) {
        if let (Ok(name), Ok(value)) = (
            reqwest::header::HeaderName::from_bytes(key.as_bytes()),
            reqwest::header::HeaderValue::from_str(&value),
        ) {
            self.0.insert(name, value);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::upstream_url;

    #[test]
    fn upstream_url_preserves_path_and_query() {
        let target = "http://backend:8080".parse().unwrap();
        assert_eq!(
            upstream_url(&target, "/api/v2/bookmarks", Some("visibility=public")).as_str(),
            "http://backend:8080/api/v2/bookmarks?visibility=public"
        );
    }
}

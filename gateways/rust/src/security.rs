use axum::body::Body;
use axum::extract::State;
use axum::http::{HeaderMap, HeaderValue, Method, Request, StatusCode, header};
use axum::middleware::Next;
use axum::response::Response;
use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use url::Url;

use crate::AppState;

pub const SESSION_COOKIE: &str = "stackverse_session";
pub const LOGIN_STATE_COOKIE: &str = "stackverse_login_state";
pub const CSRF_COOKIE: &str = "XSRF-TOKEN";
pub const CSRF_HEADER: &str = "X-XSRF-TOKEN";

pub const CONTENT_SECURITY_POLICY: &str =
    "default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'";
pub const STRICT_TRANSPORT_SECURITY: &str = "max-age=31536000; includeSubDomains";

pub async fn security_headers(
    State(state): State<AppState>,
    request: Request<Body>,
    next: Next,
) -> Response {
    let is_api = is_api_path(request.uri().path());
    let secure = state.config.cookies_secure();
    let mut response = next.run(request).await;
    if is_api {
        apply_api_headers(response.headers_mut(), secure);
    } else {
        apply_document_headers(response.headers_mut(), secure);
    }
    response
}

pub async fn csrf_cookie(
    State(state): State<AppState>,
    request: Request<Body>,
    next: Next,
) -> Response {
    let needs_cookie = cookie_value(request.headers(), CSRF_COOKIE).is_none();
    let mut response = next.run(request).await;
    if needs_cookie {
        append_set_cookie(
            response.headers_mut(),
            &cookie(
                CSRF_COOKIE,
                &new_csrf_token(),
                "/",
                None,
                state.config.cookies_secure(),
                false,
            ),
        );
    }
    response
}

pub fn apply_api_headers(headers: &mut HeaderMap, secure: bool) {
    headers.insert(
        header::X_CONTENT_TYPE_OPTIONS,
        HeaderValue::from_static("nosniff"),
    );
    if secure {
        headers.insert(
            header::STRICT_TRANSPORT_SECURITY,
            HeaderValue::from_static(STRICT_TRANSPORT_SECURITY),
        );
    }
}

pub fn apply_document_headers(headers: &mut HeaderMap, secure: bool) {
    apply_api_headers(headers, secure);
    headers.insert(
        header::REFERRER_POLICY,
        HeaderValue::from_static("same-origin"),
    );
    headers.insert(
        header::CONTENT_SECURITY_POLICY,
        HeaderValue::from_static(CONTENT_SECURITY_POLICY),
    );
    headers.insert(header::X_FRAME_OPTIONS, HeaderValue::from_static("DENY"));
    headers.insert(
        header::HeaderName::from_static("cross-origin-opener-policy"),
        HeaderValue::from_static("same-origin"),
    );
    headers.insert(
        header::HeaderName::from_static("cross-origin-resource-policy"),
        HeaderValue::from_static("same-origin"),
    );
}

pub fn cookie_value(headers: &HeaderMap, name: &str) -> Option<String> {
    let raw = headers.get(header::COOKIE)?.to_str().ok()?;
    raw.split(';').find_map(|part| {
        let (candidate, value) = part.trim().split_once('=')?;
        (candidate == name).then(|| value.to_string())
    })
}

pub fn append_set_cookie(headers: &mut HeaderMap, value: &str) {
    if let Ok(value) = HeaderValue::from_str(value) {
        headers.append(header::SET_COOKIE, value);
    }
}

pub fn cookie(
    name: &str,
    value: &str,
    path: &str,
    max_age: Option<i64>,
    secure: bool,
    http_only: bool,
) -> String {
    let mut parts = vec![format!("{name}={value}"), format!("Path={path}")];
    if let Some(max_age) = max_age {
        parts.push(format!("Max-Age={max_age}"));
        if max_age <= 0 {
            parts.push("Expires=Thu, 01 Jan 1970 00:00:00 GMT".to_string());
        }
    }
    if secure {
        parts.push("Secure".to_string());
    }
    if http_only {
        parts.push("HttpOnly".to_string());
    }
    parts.push("SameSite=Lax".to_string());
    parts.join("; ")
}

pub fn session_cookie(value: &str, secure: bool) -> String {
    cookie(
        SESSION_COOKIE,
        value,
        "/",
        Some(crate::session::SESSION_TTL.as_secs() as i64),
        secure,
        true,
    )
}

pub fn clear_session_cookie(secure: bool) -> String {
    cookie(SESSION_COOKIE, "", "/", Some(0), secure, true)
}

pub fn login_state_cookie(value: &str, secure: bool) -> String {
    cookie(
        LOGIN_STATE_COOKIE,
        value,
        "/auth/callback",
        Some(crate::session::STATE_TTL.as_secs() as i64),
        secure,
        true,
    )
}

pub fn clear_login_state_cookie(secure: bool) -> String {
    cookie(
        LOGIN_STATE_COOKIE,
        "",
        "/auth/callback",
        Some(0),
        secure,
        true,
    )
}

pub fn valid_csrf(headers: &HeaderMap, method: &Method) -> bool {
    if !is_state_changing(method) {
        return true;
    }
    let Some(cookie) = cookie_value(headers, CSRF_COOKIE) else {
        return false;
    };
    let Some(header) = headers
        .get(CSRF_HEADER)
        .and_then(|value| value.to_str().ok())
    else {
        return false;
    };
    constant_time_eq(header.as_bytes(), cookie.as_bytes())
}

pub fn same_origin_state_change_allowed(
    headers: &HeaderMap,
    method: &Method,
    path: &str,
    expected_origin: &str,
) -> bool {
    if !is_state_changing(method) || !is_api_path(path) {
        return true;
    }

    if let Some(origin) = headers
        .get(header::ORIGIN)
        .and_then(|value| value.to_str().ok())
    {
        if canonical_origin_or_empty(origin).as_deref() != Some(expected_origin) {
            return false;
        }
    }

    match headers
        .get("sec-fetch-site")
        .and_then(|value| value.to_str().ok())
    {
        None => true,
        Some(value) => {
            value.eq_ignore_ascii_case("same-origin") || value.eq_ignore_ascii_case("none")
        }
    }
}

pub fn expected_origin(public_url: &Url) -> String {
    let scheme = public_url.scheme().to_ascii_lowercase();
    let host = public_url
        .host_str()
        .unwrap_or_default()
        .to_ascii_lowercase();
    match public_url.port() {
        None => format!("{scheme}://{}", bracket_host(&host)),
        Some(80) if scheme == "http" => format!("{scheme}://{}", bracket_host(&host)),
        Some(443) if scheme == "https" => format!("{scheme}://{}", bracket_host(&host)),
        Some(port) => format!("{scheme}://{}:{port}", bracket_host(&host)),
    }
}

pub fn canonical_origin_or_empty(raw: &str) -> Option<String> {
    let parsed = Url::parse(raw).ok()?;
    if parsed.host_str().is_none()
        || parsed.path() != "/"
        || parsed.query().is_some()
        || parsed.fragment().is_some()
    {
        return None;
    }
    Some(expected_origin(&parsed))
}

pub fn is_api_path(path: &str) -> bool {
    path == "/api" || path.starts_with("/api/")
}

fn bracket_host(host: &str) -> String {
    if host.contains(':') && !host.starts_with('[') {
        format!("[{host}]")
    } else {
        host.to_string()
    }
}

fn is_state_changing(method: &Method) -> bool {
    matches!(
        *method,
        Method::POST | Method::PUT | Method::PATCH | Method::DELETE
    )
}

fn new_csrf_token() -> String {
    let bytes: [u8; 16] = rand::random();
    URL_SAFE_NO_PAD.encode(bytes)
}

pub fn constant_time_eq(left: &[u8], right: &[u8]) -> bool {
    if left.len() != right.len() {
        return false;
    }
    let mut diff = 0u8;
    for (l, r) in left.iter().zip(right) {
        diff |= l ^ r;
    }
    diff == 0
}

pub fn csrf_problem() -> Response {
    crate::problems::problem(
        StatusCode::FORBIDDEN,
        "Forbidden",
        "Missing or mismatched X-XSRF-TOKEN header.",
    )
}

pub fn cross_origin_problem() -> Response {
    crate::problems::problem(
        StatusCode::FORBIDDEN,
        "Forbidden",
        "Cross-origin state-changing requests are not supported.",
    )
}

#[cfg(test)]
mod tests {
    use axum::http::{HeaderMap, HeaderValue, Method, header};

    use super::{canonical_origin_or_empty, expected_origin, same_origin_state_change_allowed};

    #[test]
    fn canonical_origin_handles_default_ports_and_ipv6() {
        let parsed = "https://Example.COM:443/path".parse().unwrap();
        assert_eq!(expected_origin(&parsed), "https://example.com");

        let parsed = "http://[::1]:8000".parse().unwrap();
        assert_eq!(expected_origin(&parsed), "http://[::1]:8000");
    }

    #[test]
    fn origin_parser_rejects_non_origins() {
        for raw in [
            "not a url",
            "https://stackverse.example/path",
            "https://stackverse.example?x=1",
            "https://stackverse.example#fragment",
        ] {
            assert_eq!(canonical_origin_or_empty(raw), None);
        }
    }

    #[test]
    fn state_changes_require_exact_browser_signals() {
        let mut headers = HeaderMap::new();
        headers.insert(
            header::ORIGIN,
            HeaderValue::from_static("http://localhost:8000"),
        );
        headers.insert("sec-fetch-site", HeaderValue::from_static("same-origin"));
        assert!(same_origin_state_change_allowed(
            &headers,
            &Method::POST,
            "/api/v1/bookmarks",
            "http://localhost:8000"
        ));

        headers.insert("sec-fetch-site", HeaderValue::from_static("same-site"));
        assert!(!same_origin_state_change_allowed(
            &headers,
            &Method::POST,
            "/api/v1/bookmarks",
            "http://localhost:8000"
        ));
    }
}

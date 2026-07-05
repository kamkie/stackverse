use std::collections::{BTreeMap, HashMap};

use axum::body::Body;
use axum::http::{HeaderMap, HeaderValue, Response, StatusCode, Uri, header};
use chrono::{DateTime, Timelike, Utc};
use serde::Serialize;
use sqlx::{PgPool, Row};
use url::form_urlencoded;

use crate::AppState;

#[derive(Debug, Clone)]
pub struct FieldViolation {
    pub field: &'static str,
    pub message_key: &'static str,
}

#[derive(Default)]
pub struct Validator {
    fields: Vec<FieldViolation>,
}

impl Validator {
    pub fn reject(&mut self, field: &'static str, message_key: &'static str) {
        self.fields.push(FieldViolation { field, message_key });
    }

    pub fn check(&mut self, condition: bool, field: &'static str, message_key: &'static str) {
        if !condition {
            self.reject(field, message_key);
        }
    }

    pub fn into_fields(self) -> Vec<FieldViolation> {
        self.fields
    }

    pub fn is_empty(&self) -> bool {
        self.fields.is_empty()
    }
}

#[derive(Serialize)]
struct ProblemBody {
    #[serde(rename = "type")]
    kind: &'static str,
    title: &'static str,
    status: u16,
    #[serde(skip_serializing_if = "Option::is_none")]
    detail: Option<String>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    errors: Vec<ProblemField>,
}

#[derive(Serialize)]
struct ProblemField {
    field: &'static str,
    #[serde(rename = "messageKey")]
    message_key: &'static str,
    message: String,
}

pub async fn problem(
    state: &AppState,
    headers: &HeaderMap,
    uri: &Uri,
    status: StatusCode,
    title: &'static str,
    detail: impl Into<Option<String>>,
) -> Response<Body> {
    problem_with_fields(state, headers, uri, status, title, detail, Vec::new()).await
}

pub async fn problem_key(
    state: &AppState,
    headers: &HeaderMap,
    uri: &Uri,
    status: StatusCode,
    title: &'static str,
    detail_key: &'static str,
) -> Response<Body> {
    let detail = localize(state, headers, uri, detail_key).await;
    problem(state, headers, uri, status, title, Some(detail)).await
}

pub async fn validation_problem(
    state: &AppState,
    headers: &HeaderMap,
    uri: &Uri,
    fields: Vec<FieldViolation>,
) -> Response<Body> {
    if !fields.is_empty() {
        let names = fields
            .iter()
            .map(|field| field.field)
            .collect::<Vec<_>>()
            .join(",");
        tracing::info!(
            event = "input_validation_failed",
            outcome = "failure",
            error_code = "validation_failed",
            fields = %names,
            "Request validation failed"
        );
    }
    problem_with_fields(
        state,
        headers,
        uri,
        StatusCode::BAD_REQUEST,
        "Bad Request",
        Some("Request validation failed.".to_string()),
        fields,
    )
    .await
}

async fn problem_with_fields(
    state: &AppState,
    headers: &HeaderMap,
    uri: &Uri,
    status: StatusCode,
    title: &'static str,
    detail: impl Into<Option<String>>,
    fields: Vec<FieldViolation>,
) -> Response<Body> {
    let mut errors = Vec::with_capacity(fields.len());
    for field in fields {
        errors.push(ProblemField {
            field: field.field,
            message_key: field.message_key,
            message: localize(state, headers, uri, field.message_key).await,
        });
    }
    let body = ProblemBody {
        kind: "about:blank",
        title,
        status: status.as_u16(),
        detail: detail.into(),
        errors,
    };
    json_with_content_type(status, "application/problem+json", &body)
}

pub fn json<T: Serialize>(status: StatusCode, value: &T) -> Response<Body> {
    json_with_content_type(status, "application/json", value)
}

pub fn json_with_headers<T: Serialize>(
    status: StatusCode,
    value: &T,
    headers: &[(&'static str, String)],
) -> Response<Body> {
    let mut response = json(status, value);
    for (name, value) in headers {
        if let Ok(header_value) = HeaderValue::from_str(value) {
            response.headers_mut().insert(*name, header_value);
        }
    }
    response
}

pub fn empty(status: StatusCode) -> Response<Body> {
    Response::builder()
        .status(status)
        .body(Body::empty())
        .unwrap()
}

pub fn etag_json<T: Serialize>(
    request_headers: &HeaderMap,
    status: StatusCode,
    value: &T,
    extra_headers: &[(&'static str, String)],
) -> Response<Body> {
    let bytes = serde_json::to_vec(value).expect("serialize response");
    let etag = format!("\"{:x}\"", md5::compute(&bytes));
    let not_modified = if_none_match(request_headers, &etag);
    let mut builder = Response::builder()
        .status(if not_modified {
            StatusCode::NOT_MODIFIED
        } else {
            status
        })
        .header(header::ETAG, etag)
        .header(header::CACHE_CONTROL, "no-cache");
    for (name, value) in extra_headers {
        builder = builder.header(*name, value);
    }
    if not_modified {
        builder.body(Body::empty()).unwrap()
    } else {
        builder
            .header(header::CONTENT_TYPE, "application/json")
            .body(Body::from(bytes))
            .unwrap()
    }
}

fn json_with_content_type<T: Serialize>(
    status: StatusCode,
    content_type: &'static str,
    value: &T,
) -> Response<Body> {
    let body = serde_json::to_vec(value).expect("serialize response");
    Response::builder()
        .status(status)
        .header(header::CONTENT_TYPE, content_type)
        .body(Body::from(body))
        .unwrap()
}

fn if_none_match(headers: &HeaderMap, etag: &str) -> bool {
    headers
        .get(header::IF_NONE_MATCH)
        .and_then(|value| value.to_str().ok())
        .is_some_and(|raw| {
            raw.split(',').any(|candidate| {
                let candidate = candidate.trim();
                candidate == "*" || candidate == etag
            })
        })
}

pub async fn internal_error(
    state: &AppState,
    headers: &HeaderMap,
    uri: &Uri,
    err: anyhow::Error,
) -> Response<Body> {
    tracing::error!(
        error = %err,
        path = %uri.path(),
        "Unhandled error serving request"
    );
    problem(
        state,
        headers,
        uri,
        StatusCode::INTERNAL_SERVER_ERROR,
        "Internal Server Error",
        None,
    )
    .await
}

pub async fn not_found(state: &AppState, headers: &HeaderMap, uri: &Uri) -> Response<Body> {
    problem(
        state,
        headers,
        uri,
        StatusCode::NOT_FOUND,
        "Not Found",
        None,
    )
    .await
}

pub async fn unauthorized(state: &AppState, headers: &HeaderMap, uri: &Uri) -> Response<Body> {
    problem(
        state,
        headers,
        uri,
        StatusCode::UNAUTHORIZED,
        "Unauthorized",
        Some("Authentication is required.".to_string()),
    )
    .await
}

pub async fn forbidden(state: &AppState, headers: &HeaderMap, uri: &Uri) -> Response<Body> {
    problem(
        state,
        headers,
        uri,
        StatusCode::FORBIDDEN,
        "Forbidden",
        Some("You do not have the role required for this operation.".to_string()),
    )
    .await
}

pub async fn conflict(
    state: &AppState,
    headers: &HeaderMap,
    uri: &Uri,
    detail: impl Into<String>,
) -> Response<Body> {
    problem(
        state,
        headers,
        uri,
        StatusCode::CONFLICT,
        "Conflict",
        Some(detail.into()),
    )
    .await
}

pub fn parse_query(uri: &Uri) -> HashMap<String, Vec<String>> {
    let mut params: HashMap<String, Vec<String>> = HashMap::new();
    if let Some(query) = uri.query() {
        for (key, value) in form_urlencoded::parse(query.as_bytes()) {
            params
                .entry(key.into_owned())
                .or_default()
                .push(value.into_owned());
        }
    }
    params
}

pub fn first(params: &HashMap<String, Vec<String>>, name: &str) -> String {
    params
        .get(name)
        .and_then(|values| values.first())
        .cloned()
        .unwrap_or_default()
}

pub fn parse_page(params: &HashMap<String, Vec<String>>) -> Result<(i64, i64), String> {
    let page = parse_int(params, "page", 0)?;
    let size = parse_size(params)?;
    if page < 0 {
        return Err("page must not be negative".to_string());
    }
    Ok((page, size))
}

pub fn parse_size(params: &HashMap<String, Vec<String>>) -> Result<i64, String> {
    let size = parse_int(params, "size", 20)?;
    if !(1..=100).contains(&size) {
        return Err("size must be between 1 and 100".to_string());
    }
    Ok(size)
}

fn parse_int(
    params: &HashMap<String, Vec<String>>,
    name: &str,
    fallback: i64,
) -> Result<i64, String> {
    let raw = first(params, name);
    if raw.is_empty() {
        return Ok(fallback);
    }
    raw.parse::<i64>()
        .map_err(|_| format!("{name} must be an integer"))
}

pub fn page_response<T: Serialize>(
    items: Vec<T>,
    page: i64,
    size: i64,
    total_items: i64,
) -> Page<T> {
    let total_pages = if total_items == 0 {
        0
    } else {
        (total_items + size - 1) / size
    };
    Page {
        items,
        page,
        size,
        total_items,
        total_pages,
    }
}

#[derive(Serialize)]
pub struct Page<T: Serialize> {
    pub items: Vec<T>,
    pub page: i64,
    pub size: i64,
    #[serde(rename = "totalItems")]
    pub total_items: i64,
    #[serde(rename = "totalPages")]
    pub total_pages: i64,
}

pub fn escape_like(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    for ch in value.chars() {
        match ch {
            '%' | '_' | '\\' => {
                out.push('\\');
                out.push(ch);
            }
            _ => out.push(ch),
        }
    }
    out
}

pub fn rune_len(value: &Option<String>) -> usize {
    value.as_ref().map_or(0, |text| text.chars().count())
}

pub fn now_utc() -> DateTime<Utc> {
    let now = Utc::now();
    now.with_nanosecond(now.timestamp_subsec_micros() * 1_000)
        .unwrap_or(now)
}

pub async fn localize(state: &AppState, headers: &HeaderMap, uri: &Uri, key: &str) -> String {
    let language = resolve_language(&state.pool, headers, uri).await;
    if let Some(text) = text_for(&state.pool, key, &language).await {
        return text;
    }
    if language != "en"
        && let Some(text) = text_for(&state.pool, key, "en").await
    {
        return text;
    }
    key.to_string()
}

pub async fn resolve_language(pool: &PgPool, headers: &HeaderMap, uri: &Uri) -> String {
    let supported = distinct_languages(pool).await.unwrap_or_default();
    let params = parse_query(uri);
    let explicit = first(&params, "lang");
    if supported.get(&explicit).copied().unwrap_or(false) {
        return explicit;
    }
    if let Some(raw) = headers
        .get(header::ACCEPT_LANGUAGE)
        .and_then(|value| value.to_str().ok())
    {
        for language in accepted_languages(raw) {
            if supported.get(&language).copied().unwrap_or(false) {
                return language;
            }
        }
    }
    "en".to_string()
}

async fn distinct_languages(pool: &PgPool) -> anyhow::Result<BTreeMap<String, bool>> {
    let rows = sqlx::query("select distinct language from messages")
        .fetch_all(pool)
        .await?;
    let mut supported = BTreeMap::new();
    for row in rows {
        let language: String = row.try_get("language")?;
        supported.insert(language, true);
    }
    Ok(supported)
}

async fn text_for(pool: &PgPool, key: &str, language: &str) -> Option<String> {
    sqlx::query_scalar::<_, String>("select text from messages where key = $1 and language = $2")
        .bind(key)
        .bind(language)
        .fetch_optional(pool)
        .await
        .ok()
        .flatten()
}

fn accepted_languages(header: &str) -> Vec<String> {
    let mut entries = Vec::new();
    for (index, part) in header.split(',').enumerate() {
        let part = part.trim();
        if part.is_empty() {
            continue;
        }
        let mut pieces = part.split(';');
        let tag = pieces.next().unwrap_or_default().trim();
        if tag.is_empty() || tag == "*" {
            continue;
        }
        let mut quality = 1.0;
        for parameter in pieces {
            if let Some((name, value)) = parameter.trim().split_once('=')
                && name.trim() == "q"
                && let Ok(parsed) = value.trim().parse::<f64>()
            {
                quality = parsed;
            }
        }
        if quality <= 0.0 {
            continue;
        }
        let code = tag
            .split('-')
            .next()
            .unwrap_or_default()
            .to_ascii_lowercase();
        entries.push((index, quality, code));
    }
    entries.sort_by(|a, b| {
        b.1.partial_cmp(&a.1)
            .unwrap_or(std::cmp::Ordering::Equal)
            .then(a.0.cmp(&b.0))
    });
    entries.into_iter().map(|(_, _, code)| code).collect()
}

#[cfg(test)]
mod tests {
    use axum::http::{HeaderMap, HeaderValue, StatusCode, Uri, header};
    use serde_json::json;

    use super::{
        accepted_languages, escape_like, etag_json, first, page_response, parse_page, parse_query,
        parse_size, rune_len,
    };

    #[test]
    fn parse_query_decodes_and_groups_repeated_parameters() {
        let uri: Uri = "/api/v1/bookmarks?tag=rust&tag=web&q=hello%20world"
            .parse()
            .unwrap();

        let params = parse_query(&uri);

        assert_eq!(
            params.get("tag").unwrap(),
            &vec!["rust".to_string(), "web".to_string()]
        );
        assert_eq!(first(&params, "q"), "hello world");
        assert_eq!(first(&params, "missing"), "");
    }

    #[test]
    fn parse_page_uses_defaults_and_validates_bounds() {
        let defaults: Uri = "/api/v1/bookmarks".parse().unwrap();
        assert_eq!(parse_page(&parse_query(&defaults)).unwrap(), (0, 20));

        let explicit: Uri = "/api/v1/bookmarks?page=2&size=100".parse().unwrap();
        assert_eq!(parse_page(&parse_query(&explicit)).unwrap(), (2, 100));

        let invalid_size: Uri = "/api/v1/bookmarks?size=101".parse().unwrap();
        assert_eq!(
            parse_page(&parse_query(&invalid_size)).unwrap_err(),
            "size must be between 1 and 100"
        );

        let invalid_page: Uri = "/api/v1/bookmarks?page=-1".parse().unwrap();
        assert_eq!(
            parse_page(&parse_query(&invalid_page)).unwrap_err(),
            "page must not be negative"
        );
    }

    #[test]
    fn parse_size_rejects_non_integer_and_out_of_range_values() {
        let non_integer: Uri = "/api/v2/bookmarks?size=abc".parse().unwrap();
        assert_eq!(
            parse_size(&parse_query(&non_integer)).unwrap_err(),
            "size must be an integer"
        );

        let zero: Uri = "/api/v2/bookmarks?size=0".parse().unwrap();
        assert_eq!(
            parse_size(&parse_query(&zero)).unwrap_err(),
            "size must be between 1 and 100"
        );
    }

    #[test]
    fn page_response_calculates_total_pages() {
        let page = page_response(vec!["a", "b"], 1, 2, 5);

        assert_eq!(page.page, 1);
        assert_eq!(page.size, 2);
        assert_eq!(page.total_items, 5);
        assert_eq!(page.total_pages, 3);
    }

    #[test]
    fn page_response_reports_zero_pages_for_empty_results() {
        let page = page_response::<String>(Vec::new(), 0, 20, 0);

        assert_eq!(page.items.len(), 0);
        assert_eq!(page.total_items, 0);
        assert_eq!(page.total_pages, 0);
    }

    #[test]
    fn escape_like_escapes_sql_wildcards_and_escape_character() {
        assert_eq!(escape_like(r"100%\_"), r"100\%\\\_");
    }

    #[test]
    fn rune_len_counts_characters_not_bytes_and_treats_none_as_zero() {
        assert_eq!(rune_len(&Some("a\u{1f600}".to_string())), 2);
        assert_eq!(rune_len(&None), 0);
    }

    #[test]
    fn accepted_languages_sorts_by_quality_and_ignores_wildcards() {
        assert_eq!(
            accepted_languages("pl-PL;q=0.8, en;q=0.9, *;q=1, fr;q=0"),
            vec!["en".to_string(), "pl".to_string()]
        );
    }

    #[test]
    fn accepted_languages_preserves_header_order_for_equal_quality_values() {
        assert_eq!(
            accepted_languages("fr-CA;q=0.7, pl-PL;q=0.7, en-US;q=0.9"),
            vec!["en".to_string(), "fr".to_string(), "pl".to_string()]
        );
    }

    #[test]
    fn etag_json_returns_not_modified_for_matching_if_none_match() {
        let body = json!({ "language": "en", "messages": {} });
        let first = etag_json(&HeaderMap::new(), StatusCode::OK, &body, &[]);
        let etag = first.headers().get(header::ETAG).unwrap().to_str().unwrap();

        let mut headers = HeaderMap::new();
        headers.insert(
            header::IF_NONE_MATCH,
            HeaderValue::from_str(&format!("\"other\", {etag}")).unwrap(),
        );
        let second = etag_json(&headers, StatusCode::OK, &body, &[]);

        assert_eq!(second.status(), StatusCode::NOT_MODIFIED);
        assert!(second.headers().get(header::CONTENT_TYPE).is_none());
    }

    #[test]
    fn etag_json_treats_wildcard_if_none_match_as_not_modified() {
        let body = json!({ "items": [] });
        let mut headers = HeaderMap::new();
        headers.insert(header::IF_NONE_MATCH, HeaderValue::from_static("*"));

        let response = etag_json(&headers, StatusCode::OK, &body, &[]);

        assert_eq!(response.status(), StatusCode::NOT_MODIFIED);
        assert_eq!(
            response
                .headers()
                .get(header::CACHE_CONTROL)
                .unwrap()
                .to_str()
                .unwrap(),
            "no-cache"
        );
        assert!(response.headers().get(header::ETAG).is_some());
        assert!(response.headers().get(header::CONTENT_TYPE).is_none());
    }
}

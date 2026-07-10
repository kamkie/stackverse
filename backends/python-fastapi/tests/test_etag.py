import json

from starlette.requests import Request

from stackverse_backend.etag import response_with_etag
from stackverse_backend.schemas import MessageBundle


def make_request(if_none_match: str | None = None) -> Request:
    headers = []
    if if_none_match is not None:
        headers.append((b"if-none-match", if_none_match.encode("ascii")))
    return Request(
        {
            "type": "http",
            "method": "GET",
            "path": "/api/v1/messages/bundle",
            "headers": headers,
            "query_string": b"",
        }
    )


def test_response_with_etag_serializes_compact_json_and_cache_headers() -> None:
    response = response_with_etag(
        make_request(),
        {"language": "pl", "messages": {"ui.greeting": "Czesc"}},
        MessageBundle,
        {"Content-Language": "pl"},
    )

    assert response.status_code == 200
    assert response.media_type == "application/json"
    assert response.headers["Cache-Control"] == "no-cache"
    assert response.headers["Content-Language"] == "pl"
    assert response.headers["ETag"].startswith('"')
    assert json.loads(response.body) == {"language": "pl", "messages": {"ui.greeting": "Czesc"}}
    assert b" " not in response.body


def test_response_with_etag_returns_304_when_any_validator_matches() -> None:
    payload = {"language": "en", "messages": {}}
    fresh = response_with_etag(make_request(), payload, MessageBundle)
    cached = response_with_etag(make_request(f'"stale", {fresh.headers["ETag"]}'), payload, MessageBundle)

    assert cached.status_code == 304
    assert cached.body == b""
    assert cached.headers["ETag"] == fresh.headers["ETag"]
    assert cached.headers["Cache-Control"] == "no-cache"

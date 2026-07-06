import json

from django.test import RequestFactory

from stackverse_api.etag import response_with_etag


def make_request(if_none_match: str | None = None):
    headers = {}
    if if_none_match is not None:
        headers["HTTP_IF_NONE_MATCH"] = if_none_match
    return RequestFactory().get("/api/v1/messages/bundle", **headers)


def test_response_with_etag_serializes_compact_json_and_cache_headers() -> None:
    response = response_with_etag(
        make_request(),
        {"language": "pl", "messages": {"ui.greeting": "Czesc"}},
        {"Content-Language": "pl"},
    )

    assert response.status_code == 200
    assert response["Content-Type"] == "application/json"
    assert response["Cache-Control"] == "no-cache"
    assert response["Content-Language"] == "pl"
    assert response["ETag"].startswith('"')
    assert json.loads(response.content) == {"language": "pl", "messages": {"ui.greeting": "Czesc"}}
    assert b" " not in response.content


def test_response_with_etag_returns_304_when_any_validator_matches() -> None:
    fresh = response_with_etag(make_request(), {"items": []})
    cached = response_with_etag(make_request(f'"stale", {fresh["ETag"]}'), {"items": []})

    assert cached.status_code == 304
    assert cached.content == b""
    assert cached["ETag"] == fresh["ETag"]
    assert cached["Cache-Control"] == "no-cache"

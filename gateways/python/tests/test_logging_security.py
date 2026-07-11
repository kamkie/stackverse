from __future__ import annotations

import json
import logging
import sys

import pytest
from opentelemetry import trace
from opentelemetry.trace import NonRecordingSpan, SpanContext, TraceFlags
from starlette.requests import Request
from starlette.responses import Response

from stackverse_gateway.config import load_config
from stackverse_gateway.logging import JsonFormatter, configure_logging, log_event, sanitize_log_value
from stackverse_gateway.security import (
    CONTENT_SECURITY_POLICY,
    STRICT_TRANSPORT_SECURITY,
    apply_security_headers,
    canonical_public_origin,
    has_valid_csrf,
    is_same_origin_state_change,
    issue_csrf_cookie,
)


def request(
    path: str = "/api/v1/bookmarks",
    *,
    method: str = "GET",
    headers: list[tuple[bytes, bytes]] | None = None,
) -> Request:
    return Request(
        {
            "type": "http",
            "asgi": {"version": "3.0", "spec_version": "2.3"},
            "http_version": "1.1",
            "server": ("stackverse.example", 443),
            "client": ("127.0.0.1", 50000),
            "scheme": "https",
            "method": method,
            "root_path": "",
            "path": path,
            "raw_path": path.encode(),
            "query_string": b"",
            "headers": headers or [],
        }
    )


def test_json_logging_emits_stable_event_fields_and_active_trace_context(
    capsys: pytest.CaptureFixture[str],
) -> None:
    configure_logging(load_config({"LOG_FORMAT": "json", "LOG_LEVEL": "debug", "OTEL_SDK_DISABLED": "true"}))
    context = SpanContext(
        trace_id=0x1234567890ABCDEF1234567890ABCDEF,
        span_id=0x1234567890ABCDEF,
        is_remote=False,
        trace_flags=TraceFlags(TraceFlags.SAMPLED),
    )

    with trace.use_span(NonRecordingSpan(context)):
        log_event(
            "info",
            "csrf_validation_failed",
            "denied",
            "Rejected request",
            method="POST",
            path="/api/v1/bookmarks",
        )

    payload = json.loads(capsys.readouterr().out)
    assert payload["level"] == "info"
    assert payload["logger"] == "stackverse.gateway"
    assert payload["event"] == "csrf_validation_failed"
    assert payload["outcome"] == "denied"
    assert payload["method"] == "POST"
    assert payload["trace_id"] == "1234567890abcdef1234567890abcdef"
    assert payload["span_id"] == "1234567890abcdef"


def test_text_logging_honors_level_and_does_not_emit_debug(capsys: pytest.CaptureFixture[str]) -> None:
    configured = configure_logging(
        load_config({"LOG_FORMAT": "text", "LOG_LEVEL": "warn", "OTEL_SDK_DISABLED": "true"})
    )

    log_event("debug", "debug_event", "success", "debug should stay disabled")
    log_event("warn", "dependency_call_failed", "failure", "dependency unavailable")

    output = capsys.readouterr().out
    assert configured.level == logging.WARNING
    assert "debug should stay disabled" not in output
    assert "WARNING stackverse.gateway dependency unavailable" in output


def test_json_formatter_includes_exception_without_client_controlled_extra_fields() -> None:
    formatter = JsonFormatter()
    try:
        raise RuntimeError("safe failure")
    except RuntimeError:
        exc_info = sys.exc_info()
        record = logging.getLogger("test").makeRecord(
            "test",
            logging.ERROR,
            __file__,
            1,
            "operation failed",
            (),
            exc_info=exc_info,
            extra={"event": "dependency_call_failed", "_private": "ignore", "empty": None},
        )

    payload = json.loads(formatter.format(record))
    assert payload["event"] == "dependency_call_failed"
    assert "RuntimeError: safe failure" in payload["exception"]
    assert "_private" not in payload
    assert "empty" not in payload


def test_log_sanitization_removes_control_characters_encodes_newlines_and_caps_length() -> None:
    assert sanitize_log_value(None) is None
    assert sanitize_log_value("first\r\nsecond\nthird\x00tail") == "first\\nsecond\\nthirdtail"
    assert sanitize_log_value("abcdefgh", max_length=5) == "abcde..."


@pytest.mark.parametrize(
    ("value", "expected"),
    [
        ("HTTP://STACKVERSE.EXAMPLE:80", "http://stackverse.example"),
        ("https://stackverse.example:443/", "https://stackverse.example"),
        ("https://stackverse.example:8443", "https://stackverse.example:8443"),
        ("https://[2001:db8::1]:8443", "https://[2001:db8::1]:8443"),
    ],
)
def test_public_origin_is_canonicalized_for_exact_browser_comparison(value: str, expected: str) -> None:
    assert canonical_public_origin(value) == expected


@pytest.mark.parametrize(
    "value",
    ["stackverse.example", "https://stackverse.example/path", "https://stackverse.example?query=1"],
)
def test_public_origin_rejects_non_origin_urls(value: str) -> None:
    with pytest.raises(ValueError, match="bare scheme/host/port"):
        canonical_public_origin(value)


@pytest.mark.parametrize(
    "origin",
    [
        b"https://stackverse.example/",
        b"http://stackverse.example",
        b"https://stackverse.example:8443",
        b"not-an-origin",
    ],
)
def test_state_changes_reject_noncanonical_or_malformed_origin(origin: bytes) -> None:
    incoming = request(method="POST", headers=[(b"origin", origin)])

    assert is_same_origin_state_change(incoming, "https://stackverse.example") is False


def test_same_origin_signals_are_both_enforced_but_non_browser_clients_remain_allowed() -> None:
    expected = "https://stackverse.example"
    valid_browser = request(
        method="DELETE",
        headers=[(b"origin", expected.encode()), (b"sec-fetch-site", b"same-origin")],
    )
    contradictory = request(
        method="DELETE",
        headers=[(b"origin", expected.encode()), (b"sec-fetch-site", b"cross-site")],
    )
    non_browser = request(method="DELETE")
    non_api = request("/auth/logout", method="POST", headers=[(b"origin", b"https://evil.example")])

    assert is_same_origin_state_change(valid_browser, expected) is True
    assert is_same_origin_state_change(contradictory, expected) is False
    assert is_same_origin_state_change(non_browser, expected) is True
    assert is_same_origin_state_change(non_api, expected) is True


def test_csrf_requires_exact_cookie_header_match_only_for_state_changes() -> None:
    matching = request(
        method="PATCH",
        headers=[(b"cookie", b"XSRF-TOKEN=token-value"), (b"x-xsrf-token", b"token-value")],
    )
    mismatch = request(
        method="PATCH",
        headers=[(b"cookie", b"XSRF-TOKEN=token-value"), (b"x-xsrf-token", b"different")],
    )

    assert has_valid_csrf(matching) is True
    assert has_valid_csrf(mismatch) is False
    assert has_valid_csrf(request(method="GET")) is True


def test_existing_csrf_cookie_is_preserved_and_not_reissued() -> None:
    incoming = request("/", headers=[(b"cookie", b"XSRF-TOKEN=existing-token")])
    response = Response()

    issue_csrf_cookie(incoming, response, secure=True)

    assert "set-cookie" not in response.headers


def test_new_csrf_cookie_is_readable_but_uses_secure_same_site_attributes() -> None:
    response = Response()

    issue_csrf_cookie(request("/"), response, secure=True)

    cookie = response.headers["set-cookie"].lower()
    assert cookie.startswith("xsrf-token=")
    assert "httponly" not in cookie
    assert "secure" in cookie
    assert "samesite=lax" in cookie
    assert "path=/" in cookie


def test_security_headers_are_full_for_auth_and_minimal_for_api() -> None:
    auth_response = Response()
    api_response = Response(headers={"cache-control": "no-cache", "etag": '"v1"'})

    apply_security_headers(request("/auth/session"), auth_response, https_public_mode=True)
    apply_security_headers(request("/api/v1/messages/bundle"), api_response, https_public_mode=False)

    assert auth_response.headers["strict-transport-security"] == STRICT_TRANSPORT_SECURITY
    assert auth_response.headers["content-security-policy"] == CONTENT_SECURITY_POLICY
    assert auth_response.headers["referrer-policy"] == "same-origin"
    assert api_response.headers["x-content-type-options"] == "nosniff"
    assert "content-security-policy" not in api_response.headers
    assert api_response.headers["cache-control"] == "no-cache"
    assert api_response.headers["etag"] == '"v1"'

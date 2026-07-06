from __future__ import annotations

from collections.abc import AsyncIterator, Iterable

import httpx
from opentelemetry import propagate
from starlette.background import BackgroundTask
from starlette.requests import Request
from starlette.responses import Response, StreamingResponse

from .logging import log_event
from .problems import problem_response
from .sessions import now_ms

_STRIPPED_REQUEST_HEADERS = {
    "authorization",
    "connection",
    "content-length",
    "cookie",
    "host",
    "keep-alive",
    "proxy-authenticate",
    "proxy-authorization",
    "te",
    "trailer",
    "transfer-encoding",
    "upgrade",
    "x-xsrf-token",
}

_STRIPPED_RESPONSE_HEADERS = {
    "connection",
    "keep-alive",
    "proxy-authenticate",
    "proxy-authorization",
    "te",
    "trailer",
    "transfer-encoding",
    "upgrade",
}


async def proxy_request(
    request: Request,
    target_base: str,
    dependency: str,
    http_client: httpx.AsyncClient,
    access_token: str | None = None,
) -> Response:
    upstream_url = _target_url(target_base, request)
    started = now_ms()
    headers = _request_headers(request.headers.raw)
    if access_token:
        headers.append((b"authorization", f"Bearer {access_token}".encode("latin-1")))

    carrier = dict(headers)
    propagate.inject(carrier)
    headers = list(carrier.items())

    try:
        upstream_request = http_client.build_request(
            request.method,
            upstream_url,
            headers=headers,
            content=await request.body(),
        )
        upstream_response = await http_client.send(upstream_request, stream=True)
    except Exception as exc:
        log_event(
            "error",
            "dependency_call_failed",
            "failure",
            f"{dependency} upstream request failed",
            dependency=dependency,
            duration_ms=now_ms() - started,
            error_code=type(exc).__name__,
        )
        return problem_response(502, "Bad Gateway", "The upstream service is unavailable.")

    response_headers = _response_headers(upstream_response.headers.multi_items())
    if request.method == "HEAD" or upstream_response.status_code in {204, 304}:
        await upstream_response.aclose()
        return Response(status_code=upstream_response.status_code, headers=response_headers)

    return StreamingResponse(
        _response_body(upstream_response),
        status_code=upstream_response.status_code,
        headers=response_headers,
        background=BackgroundTask(upstream_response.aclose),
    )


async def _response_body(response: httpx.Response) -> AsyncIterator[bytes]:
    if response.is_stream_consumed:
        yield response.content
        return
    async for chunk in response.aiter_raw():
        yield chunk


def _target_url(target_base: str, request: Request) -> str:
    query = f"?{request.url.query}" if request.url.query else ""
    return f"{target_base}{request.url.path}{query}"


def _request_headers(raw: Iterable[tuple[bytes, bytes]]) -> list[tuple[str, str]]:
    headers: list[tuple[str, str]] = []
    for name, value in raw:
        decoded_name = name.decode("latin-1")
        if decoded_name.lower() not in _STRIPPED_REQUEST_HEADERS:
            headers.append((decoded_name, value.decode("latin-1")))
    return headers


def _response_headers(items: Iterable[tuple[str, str]]) -> dict[str, str]:
    headers: dict[str, str] = {}
    for name, value in items:
        if name.lower() not in _STRIPPED_RESPONSE_HEADERS:
            headers[name] = value
    return headers

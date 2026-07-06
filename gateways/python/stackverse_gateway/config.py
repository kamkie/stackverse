from __future__ import annotations

import os
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlsplit, urlunsplit


def _env(source: Mapping[str, str], name: str, fallback: str) -> str:
    value = source.get(name, "").strip()
    return value or fallback


def _optional_env(source: Mapping[str, str], name: str) -> str | None:
    value = source.get(name, "").strip()
    return value or None


def _int_env(source: Mapping[str, str], name: str, fallback: int) -> int:
    value = _env(source, name, str(fallback))
    try:
        return int(value)
    except ValueError as exc:
        raise ValueError(f"{name} must be an integer") from exc


def _trim_trailing_slash(value: str) -> str:
    return value.rstrip("/")


def _normalize_base_url(value: str) -> str:
    parts = urlsplit(value)
    if not parts.scheme or not parts.netloc:
        raise ValueError(f"{value!r} must include a scheme and host")
    return urlunsplit((parts.scheme, parts.netloc, parts.path.rstrip("/"), "", ""))


def _default_spa_root() -> Path:
    return Path(__file__).resolve().parent / "static"


@dataclass(frozen=True)
class GatewayConfig:
    port: int
    backend_url: str
    frontend_url: str | None
    spa_root: Path
    redis_url: str
    oidc_issuer_uri: str
    oidc_internal_issuer_uri: str | None
    oidc_client_id: str
    oidc_client_secret: str
    public_url: str
    cookies_secure: bool
    log_level: str
    log_format: str
    otel_enabled: bool


def load_config(source: Mapping[str, str] | None = None) -> GatewayConfig:
    env = os.environ if source is None else source
    public_url = _normalize_base_url(_env(env, "PUBLIC_URL", "http://localhost:8000"))
    frontend_url = _optional_env(env, "FRONTEND_URL")
    internal_issuer = _optional_env(env, "OIDC_INTERNAL_ISSUER_URI")
    return GatewayConfig(
        port=_int_env(env, "PORT", 8000),
        backend_url=_normalize_base_url(_env(env, "BACKEND_URL", "http://localhost:8080")),
        frontend_url=_normalize_base_url(frontend_url) if frontend_url else None,
        spa_root=Path(_optional_env(env, "SPA_ROOT") or _default_spa_root()),
        redis_url=_env(env, "REDIS_URL", "redis://localhost:6379"),
        oidc_issuer_uri=_trim_trailing_slash(_env(env, "OIDC_ISSUER_URI", "http://localhost:8180/realms/stackverse")),
        oidc_internal_issuer_uri=_trim_trailing_slash(internal_issuer) if internal_issuer else None,
        oidc_client_id=_env(env, "OIDC_CLIENT_ID", "stackverse-gateway"),
        oidc_client_secret=_env(env, "OIDC_CLIENT_SECRET", "stackverse-secret"),
        public_url=public_url,
        cookies_secure=urlsplit(public_url).scheme == "https",
        log_level=_env(env, "LOG_LEVEL", "info").lower(),
        log_format=_env(env, "LOG_FORMAT", "json").lower(),
        otel_enabled=_env(env, "OTEL_SDK_DISABLED", "true").lower() == "false",
    )

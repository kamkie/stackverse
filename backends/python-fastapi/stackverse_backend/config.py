from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


def _env(name: str, fallback: str) -> str:
    value = os.getenv(name, "").strip()
    return value or fallback


def _int_env(name: str, fallback: int) -> int:
    value = _env(name, str(fallback))
    try:
        return int(value)
    except ValueError as exc:
        raise RuntimeError(f"{name} must be an integer") from exc


@dataclass(frozen=True)
class Config:
    port: int
    db_host: str
    db_port: int
    db_name: str
    db_user: str
    db_password: str
    oidc_issuer_uri: str
    oidc_jwks_uri: str | None
    oidc_audience: str
    seed_messages_dir: Path
    log_level: str
    log_format: str
    otel_enabled: bool
    otel_service_name: str

    @property
    def db_conninfo(self) -> str:
        return (
            f"host={self.db_host} port={self.db_port} dbname={self.db_name} "
            f"user={self.db_user} password={self.db_password}"
        )


HERE = Path(__file__).resolve().parent

config = Config(
    port=_int_env("PORT", 8080),
    db_host=_env("DB_HOST", "localhost"),
    db_port=_int_env("DB_PORT", 5432),
    db_name=_env("DB_NAME", "stackverse"),
    db_user=_env("DB_USER", "stackverse"),
    db_password=_env("DB_PASSWORD", "stackverse"),
    oidc_issuer_uri=_env("OIDC_ISSUER_URI", "http://localhost:8180/realms/stackverse"),
    oidc_jwks_uri=os.getenv("OIDC_JWKS_URI", "").strip() or None,
    oidc_audience="stackverse-api",
    seed_messages_dir=Path(_env("SEED_MESSAGES_DIR", str(HERE.parent.parent.parent / "spec" / "messages"))),
    log_level=_env("LOG_LEVEL", "info").lower(),
    log_format=_env("LOG_FORMAT", "json").lower(),
    otel_enabled=_env("OTEL_SDK_DISABLED", "true").lower() == "false",
    otel_service_name=_env("OTEL_SERVICE_NAME", "stackverse-backend-python-fastapi"),
)

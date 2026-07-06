from __future__ import annotations

import os

os.environ.setdefault("DJANGO_SETTINGS_MODULE", "stackverse_django.settings")

import django
import uvicorn

from .config import config
from .logging_setup import log_event
from .telemetry import configure_telemetry, shutdown_telemetry


def run() -> None:
    django.setup()
    from .startup import apply_migrations, seed_messages

    configure_telemetry()
    apply_migrations()
    seed_messages()
    log_event(
        "info",
        "application_start",
        "success",
        f"Stackverse backend (python-django) listening on :{config.port}",
        port=config.port,
        db_host=config.db_host,
        db_port=config.db_port,
        db_name=config.db_name,
        oidc_issuer=config.oidc_issuer_uri,
        oidc_jwks_uri=config.oidc_jwks_uri or "(via OIDC discovery)",
        seed_messages_dir=str(config.seed_messages_dir),
        log_level=config.log_level,
        log_format=config.log_format,
        otel_enabled=config.otel_enabled,
    )
    try:
        uvicorn.run("stackverse_django.asgi:application", host="0.0.0.0", port=config.port, log_config=None)
    finally:
        log_event("info", "application_stop", "success", "Stackverse backend shutting down")
        shutdown_telemetry()


if __name__ == "__main__":
    run()

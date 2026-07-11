from __future__ import annotations

from pathlib import Path

import pytest

from stackverse_gateway.config import load_config


def test_load_config_normalizes_service_urls_and_derives_https_cookie_mode(tmp_path: Path) -> None:
    config = load_config(
        {
            "PORT": "9000",
            "BACKEND_URL": "http://backend.test:8080/base/?discarded=query",
            "FRONTEND_URL": "http://frontend.test:8080/",
            "SPA_ROOT": str(tmp_path),
            "REDIS_URL": "redis://redis.test:6379/1",
            "OIDC_ISSUER_URI": "https://identity.example/realms/stackverse/",
            "OIDC_INTERNAL_ISSUER_URI": "http://keycloak:8080/realms/stackverse/",
            "OIDC_CLIENT_ID": "client",
            "OIDC_CLIENT_SECRET": "secret",
            "PUBLIC_URL": "https://stackverse.example/",
            "LOG_LEVEL": "WARN",
            "LOG_FORMAT": "TEXT",
            "OTEL_SDK_DISABLED": "false",
        }
    )

    assert config.port == 9000
    assert config.backend_url == "http://backend.test:8080/base"
    assert config.frontend_url == "http://frontend.test:8080"
    assert config.spa_root == tmp_path
    assert config.oidc_issuer_uri == "https://identity.example/realms/stackverse"
    assert config.oidc_internal_issuer_uri == "http://keycloak:8080/realms/stackverse"
    assert config.public_url == "https://stackverse.example"
    assert config.cookies_secure is True
    assert config.log_level == "warn"
    assert config.log_format == "text"
    assert config.otel_enabled is True


def test_blank_optional_values_select_static_frontend_and_bundled_spa() -> None:
    config = load_config({"FRONTEND_URL": "  ", "SPA_ROOT": "  ", "OTEL_SDK_DISABLED": "true"})

    assert config.frontend_url is None
    assert config.spa_root.name == "static"
    assert config.cookies_secure is False
    assert config.otel_enabled is False


def test_invalid_port_fails_fast_with_variable_name() -> None:
    with pytest.raises(ValueError, match="PORT must be an integer"):
        load_config({"PORT": "not-a-port"})


@pytest.mark.parametrize(
    ("name", "value"),
    [
        ("BACKEND_URL", "backend.test:8080"),
        ("FRONTEND_URL", "/relative/frontend"),
        ("PUBLIC_URL", "stackverse.example"),
    ],
)
def test_service_base_urls_require_scheme_and_host(name: str, value: str) -> None:
    with pytest.raises(ValueError, match="must include a scheme and host"):
        load_config({name: value})

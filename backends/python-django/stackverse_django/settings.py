from __future__ import annotations

import os


def env(name: str, fallback: str) -> str:
    value = os.getenv(name, "").strip()
    return value or fallback


def int_env(name: str, fallback: int) -> int:
    try:
        return int(env(name, str(fallback)))
    except ValueError as exc:
        raise RuntimeError(f"{name} must be an integer") from exc


SECRET_KEY = env("DJANGO_SECRET_KEY", "stackverse-local-dev-only")
DEBUG = False
ALLOWED_HOSTS = ["*"]
ROOT_URLCONF = "stackverse_django.urls"
ASGI_APPLICATION = "stackverse_django.asgi.application"
DEFAULT_AUTO_FIELD = "django.db.models.BigAutoField"
USE_TZ = True
TIME_ZONE = "UTC"
APPEND_SLASH = False
LOGGING_CONFIG = None

INSTALLED_APPS = [
    "django.contrib.contenttypes",
    "django.contrib.postgres",
    "rest_framework",
    "stackverse_api",
]

MIDDLEWARE: list[str] = []

DATABASES = {
    "default": {
        "ENGINE": "django.db.backends.postgresql",
        "HOST": env("DB_HOST", "localhost"),
        "PORT": int_env("DB_PORT", 5432),
        "NAME": env("DB_NAME", "stackverse"),
        "USER": env("DB_USER", "stackverse"),
        "PASSWORD": env("DB_PASSWORD", "stackverse"),
        "CONN_MAX_AGE": 0,
    }
}

REST_FRAMEWORK = {
    "DEFAULT_AUTHENTICATION_CLASSES": ["stackverse_api.auth.StackverseJWTAuthentication"],
    "DEFAULT_PERMISSION_CLASSES": ["rest_framework.permissions.AllowAny"],
    "DEFAULT_PARSER_CLASSES": ["rest_framework.parsers.JSONParser"],
    "DEFAULT_RENDERER_CLASSES": ["rest_framework.renderers.JSONRenderer"],
    "EXCEPTION_HANDLER": "stackverse_api.exceptions.drf_exception_handler",
    "UNAUTHENTICATED_USER": None,
    "UNAUTHENTICATED_TOKEN": None,
}

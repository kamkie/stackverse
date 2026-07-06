from __future__ import annotations

import json
import logging
import sys
from datetime import UTC, datetime
from typing import Any

from opentelemetry import trace

from .config import config

LEVELS = {
    "debug": logging.DEBUG,
    "info": logging.INFO,
    "warn": logging.WARNING,
    "warning": logging.WARNING,
    "error": logging.ERROR,
    "fatal": logging.CRITICAL,
}

RESERVED = {
    "args",
    "asctime",
    "created",
    "exc_info",
    "exc_text",
    "filename",
    "funcName",
    "levelname",
    "levelno",
    "lineno",
    "message",
    "module",
    "msecs",
    "msg",
    "name",
    "pathname",
    "process",
    "processName",
    "relativeCreated",
    "stack_info",
    "thread",
    "threadName",
}


def _safe_json_value(value: Any) -> Any:
    if value is None or isinstance(value, str | int | float | bool):
        return value
    if isinstance(value, dict):
        return {str(key): _safe_json_value(nested) for key, nested in value.items()}
    if isinstance(value, list | tuple | set):
        return [_safe_json_value(nested) for nested in value]
    return repr(value)


def _json_level(levelno: int) -> str:
    if levelno == logging.WARNING:
        return "warn"
    if levelno == logging.CRITICAL:
        return "fatal"
    return logging.getLevelName(levelno).lower()


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, Any] = {
            "timestamp": datetime.fromtimestamp(record.created, UTC)
            .isoformat(timespec="milliseconds")
            .replace("+00:00", "Z"),
            "level": _json_level(record.levelno),
            "logger": record.name,
            "message": record.getMessage(),
        }
        context = trace.get_current_span().get_span_context()
        if context.is_valid:
            payload["trace_id"] = f"{context.trace_id:032x}"
            payload["span_id"] = f"{context.span_id:016x}"
        for key, value in record.__dict__.items():
            if key not in RESERVED and not key.startswith("_") and value is not None:
                payload[key] = _safe_json_value(value)
        if record.exc_info:
            payload["exception"] = self.formatException(record.exc_info)
        return json.dumps(payload, ensure_ascii=False, separators=(",", ":"))


def _configure_logging() -> logging.Logger:
    root = logging.getLogger()
    root.setLevel(LEVELS.get(config.log_level, logging.INFO))
    root.handlers.clear()

    handler = logging.StreamHandler(sys.stdout)
    handler.setLevel(LEVELS.get(config.log_level, logging.INFO))
    if config.log_format == "text":
        handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(name)s: %(message)s"))
    else:
        handler.setFormatter(JsonFormatter())
    root.addHandler(handler)
    logging.getLogger("django.server").disabled = True
    logging.getLogger("django.request").disabled = True
    logging.getLogger("uvicorn.access").disabled = True
    return logging.getLogger("stackverse.backend.python_django")


logger = _configure_logging()


def log_event(level: str, event: str, outcome: str, message: str, **fields: Any) -> None:
    logger.log(LEVELS.get(level, logging.INFO), message, extra={"event": event, "outcome": outcome, **fields})

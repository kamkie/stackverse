from __future__ import annotations

import json
import logging
import sys
from datetime import UTC, datetime
from typing import Any

from opentelemetry import trace

from .config import GatewayConfig

LOGGER_NAME = "stackverse.gateway"

_LEVELS = {
    "debug": logging.DEBUG,
    "info": logging.INFO,
    "warn": logging.WARNING,
    "warning": logging.WARNING,
    "error": logging.ERROR,
    "fatal": logging.FATAL,
}

_RESERVED = set(logging.LogRecord("", 0, "", 0, "", (), None).__dict__)


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, Any] = {
            "timestamp": datetime.fromtimestamp(record.created, UTC)
            .isoformat(timespec="milliseconds")
            .replace("+00:00", "Z"),
            "level": record.levelname.lower(),
            "logger": record.name,
            "message": record.getMessage(),
        }
        for key, value in record.__dict__.items():
            if key not in _RESERVED and not key.startswith("_") and value is not None:
                payload[key] = value
        if record.exc_info:
            payload["exception"] = self.formatException(record.exc_info)
        return json.dumps(payload, separators=(",", ":"), default=str)


def configure_logging(config: GatewayConfig) -> logging.Logger:
    level = _LEVELS.get(config.log_level, logging.INFO)
    logger = logging.getLogger(LOGGER_NAME)
    logger.handlers.clear()
    logger.propagate = False
    logger.setLevel(level)

    handler = logging.StreamHandler(sys.stdout)
    handler.setLevel(level)
    if config.log_format == "text":
        handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(name)s %(message)s"))
    else:
        handler.setFormatter(JsonFormatter())
    logger.addHandler(handler)
    return logger


def logger() -> logging.Logger:
    return logging.getLogger(LOGGER_NAME)


def sanitize_log_value(value: str | None, max_length: int = 200) -> str | None:
    if value is None:
        return None
    result = ""
    for char in value.replace("\r\n", "\n"):
        if len(result) >= max_length:
            result += "..."
            break
        if char in {"\r", "\n"}:
            result += "\\n"
        elif char >= " ":
            result += char
    return result


def log_event(level: str, event: str, outcome: str, message: str, **fields: Any) -> None:
    span = trace.get_current_span()
    context = span.get_span_context()
    if context.is_valid:
        fields.setdefault("trace_id", format(context.trace_id, "032x"))
        fields.setdefault("span_id", format(context.span_id, "016x"))
    logger().log(_LEVELS.get(level, logging.INFO), message, extra={"event": event, "outcome": outcome, **fields})

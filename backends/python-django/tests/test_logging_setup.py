import json
import logging
import sys
from types import SimpleNamespace

from opentelemetry import trace
from opentelemetry.trace import NonRecordingSpan, SpanContext, TraceFlags, TraceState

from stackverse_api import logging_setup
from stackverse_api.logging_setup import JsonFormatter


def _formatted_level(levelno: int) -> str:
    record = logging.LogRecord("test", levelno, __file__, 1, "message", (), None)
    return json.loads(JsonFormatter().format(record))["level"]


def test_json_formatter_uses_stackverse_level_tokens() -> None:
    assert _formatted_level(logging.WARNING) == "warn"
    assert _formatted_level(logging.CRITICAL) == "fatal"


def test_json_formatter_serializes_structured_values_exception_and_active_trace() -> None:
    class CustomValue:
        def __repr__(self) -> str:
            return "custom-value"

    try:
        raise ValueError("failed safely")
    except ValueError:
        record = logging.LogRecord("test", logging.ERROR, __file__, 1, "message %s", ("arg",), exc_info=sys.exc_info())
    record.context = {"numbers": (1, 2), "custom": CustomValue()}
    record.none_value = None
    span = NonRecordingSpan(
        SpanContext(
            trace_id=0x123,
            span_id=0x456,
            is_remote=False,
            trace_flags=TraceFlags(TraceFlags.SAMPLED),
            trace_state=TraceState(),
        )
    )

    with trace.use_span(span):
        payload = json.loads(JsonFormatter().format(record))

    assert payload["message"] == "message arg"
    assert payload["context"] == {"numbers": [1, 2], "custom": "custom-value"}
    assert "none_value" not in payload
    assert payload["trace_id"].endswith("0123")
    assert payload["span_id"].endswith("0456")
    assert "ValueError: failed safely" in payload["exception"]


def test_logging_configuration_honors_level_and_text_format_and_log_event_uses_structured_extra(monkeypatch) -> None:
    root = logging.getLogger()
    original_level = root.level
    original_handlers = list(root.handlers)
    try:
        monkeypatch.setattr(logging_setup, "config", SimpleNamespace(log_level="debug", log_format="text"))
        configured = logging_setup._configure_logging()
        assert root.level == logging.DEBUG
        assert len(root.handlers) == 1
        assert isinstance(root.handlers[0].formatter, logging.Formatter)
        assert not isinstance(root.handlers[0].formatter, JsonFormatter)
        assert configured.name == "stackverse.backend.python_django"

        calls: list[tuple] = []
        monkeypatch.setattr(
            logging_setup, "logger", SimpleNamespace(log=lambda *args, **kwargs: calls.append((args, kwargs)))
        )
        logging_setup.log_event("warn", "authz_denied", "denied", "Denied", actor="alice")
        assert calls == [
            (
                (logging.WARNING, "Denied"),
                {"extra": {"event": "authz_denied", "outcome": "denied", "actor": "alice"}},
            )
        ]
    finally:
        root.handlers.clear()
        root.handlers.extend(original_handlers)
        root.setLevel(original_level)

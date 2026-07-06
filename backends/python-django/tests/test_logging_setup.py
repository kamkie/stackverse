import json
import logging

from stackverse_api.logging_setup import JsonFormatter


def _formatted_level(levelno: int) -> str:
    record = logging.LogRecord("test", levelno, __file__, 1, "message", (), None)
    return json.loads(JsonFormatter().format(record))["level"]


def test_json_formatter_uses_stackverse_level_tokens() -> None:
    assert _formatted_level(logging.WARNING) == "warn"
    assert _formatted_level(logging.CRITICAL) == "fatal"

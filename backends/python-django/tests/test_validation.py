import pytest

from stackverse_api.problems import BadRequestProblem, ValidationProblem
from stackverse_api.validation import (
    validate_bookmark_input,
    validate_message_input,
    validate_query_tags,
)


def test_bookmark_input_applies_defaults_and_normalizes_tags() -> None:
    parsed = validate_bookmark_input(
        {
            "url": " https://example.com/bookmark ",
            "title": "  Title  ",
            "tags": ["Python", " python ", "django"],
            "unexpected": "ignored",
        }
    )

    assert parsed == {
        "url": "https://example.com/bookmark",
        "title": "Title",
        "notes": None,
        "tags": ["python", "django"],
        "visibility": "private",
    }


def test_bookmark_input_collects_field_errors() -> None:
    with pytest.raises(ValidationProblem) as raised:
        validate_bookmark_input({"url": "/relative", "title": "", "tags": ["no spaces!"]})

    assert {(violation.field, violation.message_key) for violation in raised.value.violations} >= {
        ("url", "validation.url.invalid"),
        ("title", "validation.title.required"),
        ("tags", "validation.tag.invalid"),
    }


def test_invalid_visibility_is_bad_request() -> None:
    with pytest.raises(BadRequestProblem):
        validate_bookmark_input({"url": "https://example.com", "title": "t", "visibility": "shared"})


def test_query_tags_are_lowercase_slugs() -> None:
    assert validate_query_tags(["Valid-Tag"]) == ["valid-tag"]
    with pytest.raises(ValidationProblem):
        validate_query_tags(["no spaces"])


def test_message_input_validation() -> None:
    parsed = validate_message_input(
        {
            "key": "ui.example.key",
            "language": "en",
            "text": "Hello",
            "description": "Shown in tests",
        }
    )

    assert parsed["key"] == "ui.example.key"
    assert parsed["language"] == "en"
    assert parsed["text"] == "Hello"

    with pytest.raises(ValidationProblem):
        validate_message_input({"key": "Not.Lower", "language": "english", "text": ""})

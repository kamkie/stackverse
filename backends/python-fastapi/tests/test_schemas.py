from fastapi.testclient import TestClient

from stackverse_backend import main
from stackverse_backend.auth import Caller, authenticate_request
from stackverse_backend.routers import bookmarks
from stackverse_backend.schemas import Bookmark, BookmarkInput, Message


def test_request_models_are_typed_and_ignore_unknown_fields() -> None:
    model = BookmarkInput.model_validate(
        {
            "url": "https://example.com",
            "title": "Example",
            "tags": ["python"],
            "unexpected": "ignored",
        }
    )

    assert model.model_dump(by_alias=True) == {
        "url": "https://example.com",
        "title": "Example",
        "notes": None,
        "tags": ["python"],
        "visibility": "private",
    }


def test_response_models_omit_optional_fields() -> None:
    bookmark = Bookmark.model_validate(
        {
            "id": "00000000-0000-0000-0000-000000000001",
            "url": "https://example.com",
            "title": "Example",
            "tags": [],
            "visibility": "private",
            "status": "active",
            "owner": "demo",
            "createdAt": "2026-01-01T00:00:00Z",
            "updatedAt": "2026-01-01T00:00:00Z",
        }
    )
    message = Message.model_validate(
        {
            "id": "00000000-0000-0000-0000-000000000002",
            "key": "ui.example",
            "language": "en",
            "text": "Example",
            "createdAt": "2026-01-01T00:00:00Z",
            "updatedAt": "2026-01-01T00:00:00Z",
        }
    )

    assert "notes" not in bookmark.model_dump(by_alias=True, exclude_none=True)
    assert "description" not in message.model_dump(by_alias=True, exclude_none=True)


def test_app_schema_declares_request_and_response_models() -> None:
    schema = main.build_app().openapi()
    operation = schema["paths"]["/api/v1/bookmarks"]["post"]

    request_schema = operation["requestBody"]["content"]["application/json"]["schema"]
    response_schema = operation["responses"]["201"]["content"]["application/json"]["schema"]
    assert operation["requestBody"]["required"] is True
    assert request_schema["anyOf"][0]["$ref"].endswith("/BookmarkInput")
    assert response_schema["$ref"].endswith("/Bookmark")
    assert set(schema["components"]["schemas"]["BookmarkInput"]["required"]) == {"url", "title"}


def test_structural_validation_uses_localized_problem_details(monkeypatch) -> None:
    app = main.build_app()
    app.dependency_overrides[authenticate_request] = lambda: Caller("demo", [])
    monkeypatch.setattr(main, "resolve_language", lambda _lang, _header: "en")
    monkeypatch.setattr(main, "localize", lambda key, _language: f"localized:{key}")
    client = TestClient(app)

    response = client.post(
        "/api/v1/bookmarks",
        headers={"Authorization": "Bearer test"},
        json={"url": 42, "title": "Example"},
    )

    assert response.status_code == 400
    assert response.headers["content-type"].startswith("application/problem+json")
    assert response.json()["errors"] == [
        {
            "field": "url",
            "messageKey": "validation.url.invalid",
            "message": "localized:validation.url.invalid",
        }
    ]


def test_route_response_model_filters_internal_and_absent_fields(monkeypatch) -> None:
    app = main.build_app()
    app.dependency_overrides[authenticate_request] = lambda: Caller("demo", [])
    monkeypatch.setattr(bookmarks, "one", lambda *_args: {})
    monkeypatch.setattr(
        bookmarks,
        "to_bookmark_response",
        lambda _row: {
            "id": "00000000-0000-0000-0000-000000000001",
            "url": "https://example.com",
            "title": "Example",
            "notes": None,
            "tags": [],
            "visibility": "private",
            "status": "active",
            "owner": "demo",
            "createdAt": "2026-01-01T00:00:00Z",
            "updatedAt": "2026-01-01T00:00:00Z",
            "internal": "must-not-leak",
        },
    )
    client = TestClient(app)

    response = client.post(
        "/api/v1/bookmarks",
        headers={"Authorization": "Bearer test"},
        json={"url": "https://example.com", "title": "Example", "unexpected": "ignored"},
    )

    assert response.status_code == 201
    assert "notes" not in response.json()
    assert "internal" not in response.json()

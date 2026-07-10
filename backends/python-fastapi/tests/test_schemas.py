from fastapi.testclient import TestClient

from stackverse_backend import main
from stackverse_backend.auth import Caller, authenticate_request
from stackverse_backend.routers import bookmarks, messages
from stackverse_backend.schemas import Bookmark, BookmarkInput, Message, UserStatusInput


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


def test_request_models_preserve_existing_wire_coercions() -> None:
    bookmark = BookmarkInput.model_validate(
        {
            "url": 42,
            "title": None,
            "notes": 42,
            "tags": "not-a-list",
            "visibility": 123,
        }
    )
    numeric_tag = BookmarkInput.model_validate({"url": "https://example.com", "title": "Example", "tags": [42]})
    active_user = UserStatusInput.model_validate({"status": "active", "reason": 42})

    assert bookmark.model_dump(by_alias=True) == {
        "url": "",
        "title": "",
        "notes": None,
        "tags": [],
        "visibility": "123",
    }
    assert numeric_tag.tags == ["42"]
    assert active_user.reason is None


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
            "createdAt": "2026-01-01T00:00:00.000Z",
            "updatedAt": "2026-01-01T00:00:00.123Z",
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
    serialized = bookmark.model_dump(by_alias=True, exclude_none=True, mode="json")
    assert serialized["createdAt"] == "2026-01-01T00:00:00.000Z"
    assert serialized["updatedAt"] == "2026-01-01T00:00:00.123Z"


def test_app_schema_declares_request_and_response_models() -> None:
    schema = main.build_app().openapi()
    body_operations = {
        ("/api/v1/bookmarks", "post"): ("BookmarkInput", {"201", "400", "401"}),
        ("/api/v1/bookmarks/{bookmark_id}", "put"): ("BookmarkInput", {"200", "400", "401", "404", "409"}),
        ("/api/v1/bookmarks/{bookmark_id}/reports", "post"): (
            "ReportInput",
            {"201", "400", "401", "404", "409"},
        ),
        ("/api/v1/reports/{report_id}", "put"): ("ReportInput", {"200", "400", "401", "404", "409"}),
        ("/api/v1/admin/reports/{report_id}", "put"): (
            "ReportResolutionInput",
            {"200", "400", "401", "403", "404"},
        ),
        ("/api/v1/admin/bookmarks/{bookmark_id}/status", "put"): (
            "BookmarkStatusInput",
            {"200", "400", "401", "403", "404"},
        ),
        ("/api/v1/admin/users/{username}/status", "put"): (
            "UserStatusInput",
            {"200", "400", "401", "403", "404", "409"},
        ),
        ("/api/v1/messages", "post"): ("MessageInput", {"201", "400", "401", "403", "409"}),
        ("/api/v1/messages/{message_id}", "put"): (
            "MessageInput",
            {"200", "400", "401", "403", "404", "409"},
        ),
    }

    for (path, method), (model, response_statuses) in body_operations.items():
        operation = schema["paths"][path][method]
        request_schema = operation["requestBody"]["content"]["application/json"]["schema"]
        assert operation["requestBody"]["required"] is True
        assert request_schema == {"$ref": f"#/components/schemas/{model}"}
        assert set(operation["responses"]) == response_statuses
        assert set(operation["responses"]["400"]["content"]) == {"application/problem+json"}
        assert operation["responses"]["400"]["content"]["application/problem+json"]["schema"] == {
            "$ref": "#/components/schemas/Problem"
        }

    bookmark_response = schema["paths"]["/api/v1/bookmarks"]["post"]["responses"]["201"]
    response_schema = bookmark_response["content"]["application/json"]["schema"]
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
            "messageKey": "validation.url.required",
            "message": "localized:validation.url.required",
        }
    ]


def test_root_body_errors_retain_localized_field_violations(monkeypatch) -> None:
    app = main.build_app()
    app.dependency_overrides[authenticate_request] = lambda: Caller("demo", [])
    monkeypatch.setattr(main, "resolve_language", lambda _lang, _header: "en")
    monkeypatch.setattr(main, "localize", lambda key, _language: f"localized:{key}")
    client = TestClient(app)
    headers = {"Authorization": "Bearer test", "Content-Type": "application/json"}

    responses = [
        client.post("/api/v1/bookmarks", headers=headers),
        client.post("/api/v1/bookmarks", headers=headers, content="null"),
        client.post("/api/v1/bookmarks", headers=headers, json=[1, 2]),
    ]

    for response in responses:
        assert response.status_code == 400
        assert response.json()["errors"] == [
            {
                "field": "url",
                "messageKey": "validation.url.required",
                "message": "localized:validation.url.required",
            },
            {
                "field": "title",
                "messageKey": "validation.title.required",
                "message": "localized:validation.title.required",
            },
        ]


def test_user_status_structural_errors_preserve_existing_detail() -> None:
    app = main.build_app()
    app.dependency_overrides[authenticate_request] = lambda: Caller("admin", ["admin"])
    client = TestClient(app)
    headers = {"Authorization": "Bearer test", "Content-Type": "application/json"}

    responses = [
        client.put("/api/v1/admin/users/demo/status", headers=headers),
        client.put("/api/v1/admin/users/demo/status", headers=headers, json={}),
        client.put("/api/v1/admin/users/demo/status", headers=headers, json={"status": 42}),
    ]

    for response in responses:
        assert response.status_code == 400
        assert response.json()["detail"] == "status is required"


def test_bookmark_status_structural_errors_stay_localized(monkeypatch) -> None:
    app = main.build_app()
    app.dependency_overrides[authenticate_request] = lambda: Caller("moderator", ["moderator"])
    monkeypatch.setattr(main, "resolve_language", lambda _lang, _header: "en")
    monkeypatch.setattr(main, "localize", lambda key, _language: f"localized:{key}")
    client = TestClient(app)

    for payload in ({}, {"status": 42}):
        response = client.put(
            "/api/v1/admin/bookmarks/00000000-0000-0000-0000-000000000001/status",
            headers={"Authorization": "Bearer test"},
            json=payload,
        )

        assert response.status_code == 400
        assert response.json()["errors"] == [
            {
                "field": "status",
                "messageKey": "validation.bookmark-status.invalid",
                "message": "localized:validation.bookmark-status.invalid",
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


def test_etag_route_validates_and_filters_through_response_model(monkeypatch) -> None:
    app = main.build_app()
    app.dependency_overrides[authenticate_request] = lambda: None
    monkeypatch.setattr(messages, "one", lambda *_args: {})
    monkeypatch.setattr(
        messages,
        "to_message_response",
        lambda _row: {
            "id": "00000000-0000-0000-0000-000000000002",
            "key": "ui.example",
            "language": "en",
            "text": "Example",
            "description": None,
            "createdAt": "2026-01-01T00:00:00Z",
            "updatedAt": "2026-01-01T00:00:00Z",
            "internal": "must-not-leak",
        },
    )
    client = TestClient(app)

    response = client.get("/api/v1/messages/00000000-0000-0000-0000-000000000002")

    assert response.status_code == 200
    assert response.headers["ETag"].startswith('"')
    assert "description" not in response.json()
    assert "internal" not in response.json()
    assert response.json()["createdAt"] == "2026-01-01T00:00:00.000Z"


def test_etag_route_rejects_invalid_internal_response(monkeypatch) -> None:
    app = main.build_app()
    app.dependency_overrides[authenticate_request] = lambda: None
    monkeypatch.setattr(messages, "one", lambda *_args: {})
    monkeypatch.setattr(messages, "to_message_response", lambda _row: {"internal": "invalid"})
    client = TestClient(app, raise_server_exceptions=False)

    response = client.get("/api/v1/messages/00000000-0000-0000-0000-000000000002")

    assert response.status_code == 500
    assert response.headers["content-type"].startswith("application/problem+json")

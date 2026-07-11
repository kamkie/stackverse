from __future__ import annotations

from fakes import ScriptedConnection, Step, bookmark_row, scripted_transaction
from fastapi.testclient import TestClient

from stackverse_backend import main
from stackverse_backend.auth import Caller, authenticate_request
from stackverse_backend.routers import bookmarks


def client_for(caller: Caller | None) -> TestClient:
    app = main.build_app()
    app.dependency_overrides[authenticate_request] = lambda: caller
    return TestClient(app)


def test_bookmark_list_versions_preserve_public_paging_and_cursor_contract(monkeypatch) -> None:
    first = bookmark_row()
    second = bookmark_row(id="00000000-0000-0000-0000-000000000002", title="Older")
    list_calls = []

    def fake_query(statement, params=()):
        list_calls.append((statement.as_string(None), params))
        return [first]

    monkeypatch.setattr(bookmarks, "query", fake_query)
    monkeypatch.setattr(bookmarks, "one", lambda *_args: {"count": 1})
    anonymous = client_for(None)

    v1 = anonymous.get("/api/v1/bookmarks?visibility=public&page=1&size=5&tag=Python")

    assert v1.status_code == 200
    assert v1.headers["Deprecation"] == "@1782864000"
    assert v1.headers["Sunset"] == "Thu, 01 Jul 2027 00:00:00 GMT"
    assert v1.headers["Link"] == '</api/v2/bookmarks>; rel="successor-version"'
    assert v1.json()["items"][0]["owner"] == "demo"
    assert list_calls[0][1] == (["python"], 5, 5)

    responses = [[first, second], [second]]
    cursor_calls = []

    def cursor_query(statement, params=()):
        cursor_calls.append((statement.as_string(None), params))
        return responses.pop(0)

    monkeypatch.setattr(bookmarks, "query", cursor_query)
    authenticated = client_for(Caller("demo", []))
    page_one = authenticated.get("/api/v2/bookmarks?size=1")
    cursor = page_one.json()["nextCursor"]
    page_two = authenticated.get(f"/api/v2/bookmarks?size=1&cursor={cursor}")

    assert page_one.status_code == 200
    assert [item["title"] for item in page_one.json()["items"]] == ["Example"]
    assert page_two.status_code == 200
    assert page_two.json() == {"items": [{**page_one.json()["items"][0], "id": second["id"], "title": "Older"}]}
    assert "created_at < %s" in cursor_calls[1][0]
    assert cursor_calls[1][1][-1] == 2


def test_bookmark_create_read_masking_delete_and_tags_use_authenticated_boundary(monkeypatch) -> None:
    row = bookmark_row(visibility="private")
    monkeypatch.setattr(bookmarks, "one", lambda *_args: row)
    client = client_for(Caller("demo", []))

    created = client.post(
        "/api/v1/bookmarks",
        json={"url": "https://example.com", "title": "Example", "visibility": "private"},
    )

    assert created.status_code == 201
    assert created.headers["Location"].startswith("/api/v1/bookmarks/")
    assert created.json()["owner"] == "demo"
    assert (
        client_for(None).post("/api/v1/bookmarks", json={"url": "https://example.com", "title": "x"}).status_code == 401
    )

    monkeypatch.setattr(bookmarks, "find_bookmark", lambda _bookmark_id: row)
    assert client.get(f"/api/v1/bookmarks/{row['id']}").status_code == 200
    assert client_for(Caller("other", [])).get(f"/api/v1/bookmarks/{row['id']}").status_code == 404

    deleted = []
    monkeypatch.setattr(bookmarks, "owned_by_caller", lambda *_args: row)
    monkeypatch.setattr(bookmarks, "execute", lambda statement, params: deleted.append((statement, params)))
    assert client.delete(f"/api/v1/bookmarks/{row['id']}").status_code == 204
    assert deleted == [("delete from bookmarks where id = %s", (row["id"],))]

    monkeypatch.setattr(bookmarks, "query", lambda *_args: [{"tag": "python", "count": 2}])
    assert client.get("/api/v1/tags").json() == {"tags": [{"tag": "python", "count": 2}]}


def test_hidden_bookmark_cannot_be_republished_and_active_bookmark_updates_atomically(monkeypatch) -> None:
    hidden = bookmark_row(status="hidden", visibility="private")
    conflict_connection = ScriptedConnection(Step("select * from bookmarks", one=hidden))
    monkeypatch.setattr(bookmarks, "transaction", lambda: scripted_transaction(conflict_connection))
    monkeypatch.setattr(main, "resolve_language", lambda *_args: "en")
    monkeypatch.setattr(main, "localize", lambda key, _language: f"localized:{key}")
    client = client_for(Caller("demo", []))

    blocked = client.put(
        f"/api/v1/bookmarks/{hidden['id']}",
        json={"url": hidden["url"], "title": hidden["title"], "visibility": "public"},
    )

    assert blocked.status_code == 409
    assert blocked.json()["detail"] == "localized:error.bookmark.hidden-publish"
    conflict_connection.assert_exhausted()

    active = bookmark_row(visibility="private")
    updated = bookmark_row(title="Updated", visibility="private")
    update_connection = ScriptedConnection(
        Step("select * from bookmarks", one=active),
        Step("update bookmarks", one=updated),
    )
    monkeypatch.setattr(bookmarks, "transaction", lambda: scripted_transaction(update_connection))

    response = client.put(
        f"/api/v1/bookmarks/{active['id']}",
        json={"url": active["url"], "title": "Updated", "visibility": "private"},
    )

    assert response.status_code == 200
    assert response.json()["title"] == "Updated"
    update_connection.assert_exhausted()

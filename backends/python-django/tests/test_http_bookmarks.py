from datetime import timedelta

import pytest

from stackverse_api.models import Bookmark
from stackverse_api.time import now_utc

pytestmark = pytest.mark.django_db


def test_bookmark_crud_enforces_auth_validation_visibility_and_unknown_field_tolerance(api_client, client_for) -> None:
    payload = {
        "url": " https://example.com/private ",
        "title": "  Private bookmark  ",
        "tags": ["Python", " python ", "django"],
        "unexpected": "ignored",
    }

    unauthenticated = api_client.post("/api/v1/bookmarks", payload, format="json")
    assert unauthenticated.status_code == 401
    assert unauthenticated["Content-Type"] == "application/problem+json"

    alice = client_for("alice")
    created = alice.post("/api/v1/bookmarks", payload, format="json")

    assert created.status_code == 201
    body = created.json()
    assert created["Location"] == f"/api/v1/bookmarks/{body['id']}"
    assert body == {
        "id": body["id"],
        "url": "https://example.com/private",
        "title": "Private bookmark",
        "tags": ["python", "django"],
        "visibility": "private",
        "status": "active",
        "owner": "alice",
        "createdAt": body["createdAt"],
        "updatedAt": body["updatedAt"],
    }

    assert api_client.get(f"/api/v1/bookmarks/{body['id']}").status_code == 404
    assert client_for("bob").get(f"/api/v1/bookmarks/{body['id']}").status_code == 404
    assert alice.get(f"/api/v1/bookmarks/{body['id']}").json()["owner"] == "alice"

    public = alice.post(
        "/api/v1/bookmarks",
        {"url": "https://example.com/public", "title": "Public", "visibility": "public"},
        format="json",
    )
    assert public.status_code == 201
    assert api_client.get(f"/api/v1/bookmarks/{public.json()['id']}").status_code == 200

    invalid = alice.post(
        "/api/v1/bookmarks",
        {"url": "/relative", "title": "", "tags": ["not a slug"]},
        format="json",
    )
    assert invalid.status_code == 400
    assert invalid["Content-Type"] == "application/problem+json"
    assert {(error["field"], error["messageKey"]) for error in invalid.json()["errors"]} >= {
        ("url", "validation.url.invalid"),
        ("title", "validation.title.required"),
        ("tags", "validation.tag.invalid"),
    }


def test_bookmark_lists_preserve_v1_headers_tag_and_query_filters_and_v2_cursor_stability(
    api_client, client_for, bookmark_factory
) -> None:
    base = now_utc() - timedelta(hours=1)
    created = [
        bookmark_factory(
            owner="alice",
            title=f"Django {index}",
            notes="Framework notes",
            tags=["python", "django"],
            visibility="public",
            created_at=base + timedelta(minutes=index),
        )
        for index in range(4)
    ]
    hidden = bookmark_factory(owner="alice", title="Hidden", status="hidden")
    bookmark_factory(owner="bob", title="Bob private")

    own_page = client_for("alice").get("/api/v1/bookmarks", {"size": 100})
    assert own_page.status_code == 200
    assert own_page["Deprecation"] == "@1782864000"
    assert own_page["Sunset"] == "Thu, 01 Jul 2027 00:00:00 GMT"
    assert own_page["Link"] == '</api/v2/bookmarks>; rel="successor-version"'
    assert str(hidden.id) in {item["id"] for item in own_page.json()["items"]}

    public_page = api_client.get("/api/v1/bookmarks?visibility=public&tag=python&tag=django&q=framework&size=2")
    assert public_page.status_code == 200
    assert public_page.json()["totalItems"] == 4
    assert len(public_page.json()["items"]) == 2

    first = api_client.get("/api/v2/bookmarks", {"visibility": "public", "size": 2})
    assert first.status_code == 200
    first_ids = [item["id"] for item in first.json()["items"]]
    assert first_ids == [str(created[3].id), str(created[2].id)]

    bookmark_factory(
        owner="newcomer",
        title="Concurrent insert",
        visibility="public",
        created_at=base + timedelta(minutes=10),
    )
    second = api_client.get(
        "/api/v2/bookmarks",
        {"visibility": "public", "size": 2, "cursor": first.json()["nextCursor"]},
    )
    second_ids = [item["id"] for item in second.json()["items"]]
    assert second_ids == [str(created[1].id), str(created[0].id)]
    assert set(first_ids).isdisjoint(second_ids)
    assert "nextCursor" not in second.json()


def test_hidden_bookmark_cannot_be_republished_and_owner_controls_updates_deletion_and_tags(
    client_for, bookmark_factory
) -> None:
    hidden = bookmark_factory(owner="alice", status="hidden", tags=["python", "security"])
    bookmark_factory(owner="alice", tags=["python"])
    alice = client_for("alice")

    conflict = alice.put(
        f"/api/v1/bookmarks/{hidden.id}",
        {"url": hidden.url, "title": hidden.title, "visibility": "public"},
        format="json",
    )
    assert conflict.status_code == 409
    assert conflict.json()["detail"] == "error.bookmark.hidden-publish"

    updated = alice.put(
        f"/api/v1/bookmarks/{hidden.id}",
        {"url": hidden.url, "title": "Still private", "visibility": "private", "tags": ["python", "security"]},
        format="json",
    )
    assert updated.status_code == 200
    hidden.refresh_from_db()
    assert hidden.title == "Still private"
    assert hidden.status == "hidden"

    tags = alice.get("/api/v1/tags")
    assert tags.status_code == 200
    assert tags.json() == {"tags": [{"tag": "python", "count": 2}, {"tag": "security", "count": 1}]}

    assert client_for("bob").delete(f"/api/v1/bookmarks/{hidden.id}").status_code == 404
    assert alice.delete(f"/api/v1/bookmarks/{hidden.id}").status_code == 204
    assert not Bookmark.objects.filter(id=hidden.id).exists()

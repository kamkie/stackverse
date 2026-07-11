from __future__ import annotations

from fakes import ScriptedConnection, Step, bookmark_row, report_row, scripted_transaction
from fastapi.testclient import TestClient
from psycopg.errors import UniqueViolation

from stackverse_backend import api, main
from stackverse_backend.auth import Caller, authenticate_request
from stackverse_backend.routers import reports


def client_for(caller: Caller | None) -> TestClient:
    app = main.build_app()
    app.dependency_overrides[authenticate_request] = lambda: caller
    return TestClient(app)


def test_report_creation_masks_ineligible_bookmarks_and_handles_duplicate_races(monkeypatch) -> None:
    client = client_for(Caller("reporter", []))
    row = report_row()
    events = []
    monkeypatch.setattr(reports, "log_event", lambda *args, **fields: events.append((args, fields)))

    created_connection = ScriptedConnection(
        Step("select visibility, status from bookmarks", one={"visibility": "public", "status": "active"}),
        Step("select 1 from reports", one=None),
        Step("insert into reports", one=row),
    )
    monkeypatch.setattr(reports, "transaction", lambda: scripted_transaction(created_connection))
    created = client.post(
        f"/api/v1/bookmarks/{row['bookmark_id']}/reports",
        json={"reason": "spam", "comment": "Please review"},
    )

    assert created.status_code == 201
    assert created.json()["reporter"] == "reporter"
    assert events[0][0][:3] == ("info", "report_created", "success")
    assert "Please review" not in str(events[0])
    created_connection.assert_exhausted()

    hidden_connection = ScriptedConnection(
        Step("select visibility, status from bookmarks", one={"visibility": "public", "status": "hidden"})
    )
    monkeypatch.setattr(reports, "transaction", lambda: scripted_transaction(hidden_connection))
    hidden = client.post(f"/api/v1/bookmarks/{row['bookmark_id']}/reports", json={"reason": "spam"})
    assert hidden.status_code == 404
    hidden_connection.assert_exhausted()

    duplicate_connection = ScriptedConnection(
        Step("select visibility, status from bookmarks", one={"visibility": "public", "status": "active"}),
        Step("select 1 from reports", one={"exists": 1}),
    )
    monkeypatch.setattr(reports, "transaction", lambda: scripted_transaction(duplicate_connection))
    duplicate = client.post(f"/api/v1/bookmarks/{row['bookmark_id']}/reports", json={"reason": "spam"})
    assert duplicate.status_code == 409
    duplicate_connection.assert_exhausted()

    race_connection = ScriptedConnection(
        Step("select visibility, status from bookmarks", one={"visibility": "public", "status": "active"}),
        Step("select 1 from reports", one=None),
        Step("insert into reports", raises=UniqueViolation("duplicate open report")),
    )
    monkeypatch.setattr(reports, "transaction", lambda: scripted_transaction(race_connection))
    raced = client.post(f"/api/v1/bookmarks/{row['bookmark_id']}/reports", json={"reason": "spam"})
    assert raced.status_code == 409
    race_connection.assert_exhausted()


def test_reporter_can_list_update_and_withdraw_only_open_owned_reports(monkeypatch) -> None:
    client = client_for(Caller("reporter", []))
    row = report_row()
    monkeypatch.setattr(reports, "query", lambda *_args: [row])
    monkeypatch.setattr(reports, "one", lambda *_args: {"count": 1})

    listed = client.get("/api/v1/reports?status=open&page=0&size=5")
    assert listed.status_code == 200
    assert listed.json()["items"][0]["id"] == row["id"]

    events = []
    monkeypatch.setattr(reports, "log_event", lambda *args, **fields: events.append((args, fields)))
    updated_row = report_row(reason="broken-link", comment="Updated")
    update_connection = ScriptedConnection(
        Step("select * from reports", one=row),
        Step("update reports set reason", one=updated_row),
    )
    monkeypatch.setattr(reports, "transaction", lambda: scripted_transaction(update_connection))
    updated = client.put(f"/api/v1/reports/{row['id']}", json={"reason": "broken-link", "comment": "Updated"})

    assert updated.status_code == 200
    assert updated.json()["reason"] == "broken-link"
    assert events[-1][0][1] == "report_updated"
    update_connection.assert_exhausted()

    withdraw_connection = ScriptedConnection(
        Step("select * from reports", one=row),
        Step("delete from reports"),
    )
    monkeypatch.setattr(reports, "transaction", lambda: scripted_transaction(withdraw_connection))
    withdrawn = client.delete(f"/api/v1/reports/{row['id']}")

    assert withdrawn.status_code == 204
    assert events[-1][0][1] == "report_withdrawn"
    withdraw_connection.assert_exhausted()

    resolved_connection = ScriptedConnection(Step("select * from reports", one=report_row(status="dismissed")))
    monkeypatch.setattr(reports, "transaction", lambda: scripted_transaction(resolved_connection))
    assert client.delete(f"/api/v1/reports/{row['id']}").status_code == 409
    resolved_connection.assert_exhausted()


def test_actioned_resolution_hides_bookmark_and_auto_resolves_open_siblings(monkeypatch) -> None:
    moderator = client_for(Caller("moderator", ["moderator"]))
    target = report_row()
    sibling = report_row(id="00000000-0000-0000-0000-000000000011", reporter="other")
    resolved_target = report_row(
        status="actioned",
        resolved_by="moderator",
        resolved_at=target["created_at"],
        resolution_note="confirmed",
    )
    resolved_sibling = report_row(
        id=sibling["id"],
        reporter="other",
        status="actioned",
        resolved_by="moderator",
        resolved_at=target["created_at"],
        resolution_note="confirmed",
    )
    connection = ScriptedConnection(
        Step("select bookmark_id from reports", one={"bookmark_id": target["bookmark_id"]}),
        Step("select id from bookmarks", one={"id": target["bookmark_id"]}),
        Step("select * from reports", one=target),
        Step("update reports", one=resolved_target),
        Step("select * from bookmarks", one=bookmark_row()),
        Step("update bookmarks set status = 'hidden'"),
        Step("select * from reports", all=[sibling]),
        Step("update reports", one=resolved_sibling),
    )
    monkeypatch.setattr(reports, "transaction", lambda: scripted_transaction(connection))
    audits = []
    events = []
    monkeypatch.setattr(api, "record_audit", lambda *args: audits.append(args))
    monkeypatch.setattr(api, "log_event", lambda *args, **fields: events.append((args, fields)))

    response = moderator.put(
        f"/api/v1/admin/reports/{target['id']}",
        json={"resolution": "actioned", "note": "confirmed"},
    )

    assert response.status_code == 200
    assert response.json()["status"] == "actioned"
    assert [call[2] for call in audits] == [
        "report.resolved",
        "bookmark.status-changed",
        "report.resolved",
    ]
    assert [event[0][1] for event in events] == [
        "report_resolved",
        "bookmark_status_changed",
        "report_resolved",
    ]
    assert events[-1][1]["auto_resolved"] is True
    connection.assert_exhausted()


def test_moderator_reopen_clears_resolution_and_rejects_another_open_report(monkeypatch) -> None:
    moderator = client_for(Caller("moderator", ["moderator"]))
    resolved = report_row(
        status="dismissed",
        resolved_by="moderator",
        resolved_at=report_row()["created_at"],
        resolution_note="old note",
    )
    reopened = report_row()
    connection = ScriptedConnection(
        Step("select * from reports", one=resolved),
        Step("select 1 from reports", one=None),
        Step("update reports", one=reopened),
    )
    monkeypatch.setattr(reports, "transaction", lambda: scripted_transaction(connection))
    audits = []
    events = []
    monkeypatch.setattr(reports, "record_audit", lambda *args: audits.append(args))
    monkeypatch.setattr(reports, "log_event", lambda *args, **fields: events.append((args, fields)))

    response = moderator.put(
        f"/api/v1/admin/reports/{resolved['id']}",
        json={"resolution": "open", "note": "must be ignored"},
    )

    assert response.status_code == 200
    assert response.json()["status"] == "open"
    assert "resolvedBy" not in response.json()
    assert audits[0][2] == "report.reopened"
    assert events[0][0][1] == "report_reopened"
    connection.assert_exhausted()

    conflict_connection = ScriptedConnection(
        Step("select * from reports", one=resolved),
        Step("select 1 from reports", one={"exists": 1}),
    )
    monkeypatch.setattr(reports, "transaction", lambda: scripted_transaction(conflict_connection))
    conflict = moderator.put(f"/api/v1/admin/reports/{resolved['id']}", json={"resolution": "open"})
    assert conflict.status_code == 409
    conflict_connection.assert_exhausted()


def test_moderation_queue_requires_role_and_orders_open_reports(monkeypatch) -> None:
    row = report_row()
    assert client_for(Caller("demo", [])).get("/api/v1/admin/reports").status_code == 403

    calls = []
    monkeypatch.setattr(reports, "query", lambda statement, params: calls.append((statement, params)) or [row])
    monkeypatch.setattr(reports, "one", lambda *_args: {"count": 1})
    response = client_for(Caller("moderator", ["moderator"])).get("/api/v1/admin/reports?size=5")

    assert response.status_code == 200
    assert response.json()["items"][0]["status"] == "open"
    assert calls[0][1] == ("open", 5, 0)

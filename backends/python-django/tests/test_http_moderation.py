import pytest

from stackverse_api.models import AuditEntry, Report
from stackverse_api.time import now_utc

pytestmark = pytest.mark.django_db


def test_report_action_hides_bookmark_resolves_siblings_and_supports_reopen_and_restore(
    api_client, client_for, bookmark_factory
) -> None:
    bookmark = bookmark_factory(owner="owner", visibility="public", title="Needs moderation")
    reporter_one = client_for("reporter-one")
    reporter_two = client_for("reporter-two")

    first = reporter_one.post(
        f"/api/v1/bookmarks/{bookmark.id}/reports",
        {"reason": "spam", "comment": "Repeated promotion"},
        format="json",
    )
    second = reporter_two.post(
        f"/api/v1/bookmarks/{bookmark.id}/reports",
        {"reason": "offensive"},
        format="json",
    )
    assert first.status_code == second.status_code == 201
    assert (
        reporter_one.post(f"/api/v1/bookmarks/{bookmark.id}/reports", {"reason": "other"}, format="json").status_code
        == 409
    )

    assert client_for("regular").get("/api/v1/admin/reports").status_code == 403
    moderator = client_for("moderator", ["moderator"])
    queue = moderator.get("/api/v1/admin/reports")
    assert queue.status_code == 200
    assert queue.json()["totalItems"] == 2

    actioned = moderator.put(
        f"/api/v1/admin/reports/{first.json()['id']}",
        {"resolution": "actioned", "note": "Confirmed"},
        format="json",
    )
    assert actioned.status_code == 200
    assert actioned.json()["status"] == "actioned"

    bookmark.refresh_from_db()
    reports = list(Report.objects.filter(bookmark=bookmark).order_by("reporter"))
    assert bookmark.status == "hidden"
    assert {report.status for report in reports} == {"actioned"}
    assert {report.resolved_by for report in reports} == {"moderator"}
    assert {report.resolution_note for report in reports} == {"Confirmed"}
    assert AuditEntry.objects.filter(action="report.resolved").count() == 2
    assert AuditEntry.objects.filter(action="bookmark.status-changed").count() == 1

    assert api_client.get(f"/api/v1/bookmarks/{bookmark.id}").status_code == 404
    assert client_for("owner").get(f"/api/v1/bookmarks/{bookmark.id}").status_code == 200

    reopened = moderator.put(
        f"/api/v1/admin/reports/{first.json()['id']}",
        {"resolution": "open", "note": "This must be ignored"},
        format="json",
    )
    assert reopened.status_code == 200
    assert reopened.json()["status"] == "open"
    assert "resolvedBy" not in reopened.json()
    assert "resolutionNote" not in reopened.json()
    bookmark.refresh_from_db()
    assert bookmark.status == "hidden"
    assert AuditEntry.objects.filter(action="report.reopened").count() == 1

    restored = moderator.put(
        f"/api/v1/admin/bookmarks/{bookmark.id}/status",
        {"status": "active", "note": "Reviewed"},
        format="json",
    )
    assert restored.status_code == 200
    assert restored.json()["status"] == "active"
    assert restored.json()["visibility"] == "public"
    assert AuditEntry.objects.filter(action="bookmark.status-changed").count() == 2


def test_reporter_can_edit_and_withdraw_only_an_owned_open_report(client_for, bookmark_factory, report_factory) -> None:
    bookmark = bookmark_factory(owner="owner", visibility="public")
    report = report_factory(bookmark, "alice", comment="Original")

    hidden_from_other_reporter = client_for("bob").put(
        f"/api/v1/reports/{report.id}", {"reason": "other"}, format="json"
    )
    assert hidden_from_other_reporter.status_code == 404

    alice = client_for("alice")
    updated = alice.put(
        f"/api/v1/reports/{report.id}",
        {"reason": "broken-link", "comment": "Now unreachable"},
        format="json",
    )
    assert updated.status_code == 200
    assert updated.json()["reason"] == "broken-link"
    assert updated.json()["comment"] == "Now unreachable"

    own_reports = alice.get("/api/v1/reports", {"status": "open"})
    assert own_reports.status_code == 200
    assert [item["id"] for item in own_reports.json()["items"]] == [str(report.id)]

    assert alice.delete(f"/api/v1/reports/{report.id}").status_code == 204
    assert not Report.objects.filter(id=report.id).exists()
    assert alice.post(f"/api/v1/bookmarks/{bookmark.id}/reports", {"reason": "spam"}, format="json").status_code == 201

    resolved = report_factory(
        bookmark,
        "alice",
        status="dismissed",
        resolved_by="moderator",
        resolved_at=now_utc(),
    )
    assert alice.put(f"/api/v1/reports/{resolved.id}", {"reason": "other"}, format="json").status_code == 409
    assert alice.delete(f"/api/v1/reports/{resolved.id}").status_code == 409


def test_report_and_moderation_inputs_preserve_masking_and_field_errors(client_for, bookmark_factory) -> None:
    private = bookmark_factory(owner="owner", visibility="private")
    reporter = client_for("reporter")

    assert (
        reporter.post(f"/api/v1/bookmarks/{private.id}/reports", {"reason": "spam"}, format="json").status_code == 404
    )
    invalid = reporter.post(
        f"/api/v1/bookmarks/{private.id}/reports",
        {"reason": "not-a-reason", "comment": "x" * 1001},
        format="json",
    )
    assert invalid.status_code == 400
    assert {error["messageKey"] for error in invalid.json()["errors"]} == {
        "validation.report.reason.invalid",
        "validation.report.comment.too-long",
    }

    moderator = client_for("moderator", ["moderator"])
    bad_status = moderator.put(f"/api/v1/admin/bookmarks/{private.id}/status", {"status": "archived"}, format="json")
    assert bad_status.status_code == 400
    assert bad_status.json()["errors"][0]["messageKey"] == "validation.bookmark-status.invalid"

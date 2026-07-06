from __future__ import annotations

import uuid

from django.contrib.postgres.fields import ArrayField
from django.contrib.postgres.indexes import GinIndex
from django.db import models
from django.db.models import Q


class UserAccount(models.Model):
    username = models.TextField(primary_key=True)
    first_seen = models.DateTimeField()
    last_seen = models.DateTimeField()
    status = models.TextField(default="active")
    blocked_reason = models.TextField(null=True, blank=True)

    class Meta:
        db_table = "user_accounts"


class Bookmark(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    owner = models.TextField()
    url = models.TextField()
    title = models.TextField()
    notes = models.TextField(null=True, blank=True)
    tags = ArrayField(models.TextField(), default=list)
    visibility = models.TextField(default="private")
    status = models.TextField(default="active")
    created_at = models.DateTimeField()
    updated_at = models.DateTimeField()

    class Meta:
        db_table = "bookmarks"
        indexes = [
            models.Index(fields=["owner", "-created_at", "-id"], name="idx_bookmarks_owner_created"),
            models.Index(
                fields=["-created_at", "-id"],
                name="idx_bookmarks_public_created",
                condition=Q(visibility="public", status="active"),
            ),
            GinIndex(fields=["tags"], name="idx_bookmarks_tags"),
        ]


class Report(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    bookmark = models.ForeignKey(Bookmark, db_column="bookmark_id", on_delete=models.CASCADE)
    reporter = models.TextField()
    reason = models.TextField()
    comment = models.TextField(null=True, blank=True)
    status = models.TextField(default="open")
    resolved_by = models.TextField(null=True, blank=True)
    resolved_at = models.DateTimeField(null=True, blank=True)
    resolution_note = models.TextField(null=True, blank=True)
    created_at = models.DateTimeField()

    class Meta:
        db_table = "reports"
        indexes = [
            models.Index(fields=["status", "created_at"], name="idx_reports_status_created"),
            models.Index(fields=["reporter", "-created_at"], name="idx_reports_reporter_created"),
        ]
        constraints = [
            models.UniqueConstraint(
                fields=["bookmark", "reporter"],
                condition=Q(status="open"),
                name="uq_reports_one_open_per_reporter",
            )
        ]


class AuditEntry(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    actor = models.TextField()
    action = models.TextField()
    target_type = models.TextField()
    target_id = models.TextField()
    detail = models.JSONField(null=True, blank=True)
    created_at = models.DateTimeField()

    class Meta:
        db_table = "audit_entries"
        indexes = [models.Index(fields=["-created_at"], name="idx_audit_entries_created")]


class Message(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    key = models.TextField()
    language = models.TextField()
    text = models.TextField()
    description = models.TextField(null=True, blank=True)
    created_at = models.DateTimeField()
    updated_at = models.DateTimeField()

    class Meta:
        db_table = "messages"
        constraints = [
            models.UniqueConstraint(fields=["key", "language"], name="uq_messages_key_language"),
        ]

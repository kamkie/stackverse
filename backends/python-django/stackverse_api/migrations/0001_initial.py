from __future__ import annotations

import uuid

import django.contrib.postgres.fields
import django.contrib.postgres.indexes
import django.db.models.deletion
from django.db import migrations, models
from django.db.models import Q


class Migration(migrations.Migration):
    initial = True

    dependencies: list[tuple[str, str]] = []

    operations = [
        migrations.CreateModel(
            name="Bookmark",
            fields=[
                ("id", models.UUIDField(default=uuid.uuid4, editable=False, primary_key=True, serialize=False)),
                ("owner", models.TextField()),
                ("url", models.TextField()),
                ("title", models.TextField()),
                ("notes", models.TextField(blank=True, null=True)),
                (
                    "tags",
                    django.contrib.postgres.fields.ArrayField(base_field=models.TextField(), default=list, size=None),
                ),
                ("visibility", models.TextField(default="private")),
                ("status", models.TextField(default="active")),
                ("created_at", models.DateTimeField()),
                ("updated_at", models.DateTimeField()),
            ],
            options={
                "db_table": "bookmarks",
            },
        ),
        migrations.CreateModel(
            name="UserAccount",
            fields=[
                ("username", models.TextField(primary_key=True, serialize=False)),
                ("first_seen", models.DateTimeField()),
                ("last_seen", models.DateTimeField()),
                ("status", models.TextField(default="active")),
                ("blocked_reason", models.TextField(blank=True, null=True)),
            ],
            options={
                "db_table": "user_accounts",
            },
        ),
        migrations.CreateModel(
            name="AuditEntry",
            fields=[
                ("id", models.UUIDField(default=uuid.uuid4, editable=False, primary_key=True, serialize=False)),
                ("actor", models.TextField()),
                ("action", models.TextField()),
                ("target_type", models.TextField()),
                ("target_id", models.TextField()),
                ("detail", models.JSONField(blank=True, null=True)),
                ("created_at", models.DateTimeField()),
            ],
            options={
                "db_table": "audit_entries",
            },
        ),
        migrations.CreateModel(
            name="Message",
            fields=[
                ("id", models.UUIDField(default=uuid.uuid4, editable=False, primary_key=True, serialize=False)),
                ("key", models.TextField()),
                ("language", models.TextField()),
                ("text", models.TextField()),
                ("description", models.TextField(blank=True, null=True)),
                ("created_at", models.DateTimeField()),
                ("updated_at", models.DateTimeField()),
            ],
            options={
                "db_table": "messages",
            },
        ),
        migrations.CreateModel(
            name="Report",
            fields=[
                ("id", models.UUIDField(default=uuid.uuid4, editable=False, primary_key=True, serialize=False)),
                ("reporter", models.TextField()),
                ("reason", models.TextField()),
                ("comment", models.TextField(blank=True, null=True)),
                ("status", models.TextField(default="open")),
                ("resolved_by", models.TextField(blank=True, null=True)),
                ("resolved_at", models.DateTimeField(blank=True, null=True)),
                ("resolution_note", models.TextField(blank=True, null=True)),
                ("created_at", models.DateTimeField()),
                (
                    "bookmark",
                    models.ForeignKey(
                        db_column="bookmark_id",
                        on_delete=django.db.models.deletion.CASCADE,
                        to="stackverse_api.bookmark",
                    ),
                ),
            ],
            options={
                "db_table": "reports",
            },
        ),
        migrations.AddIndex(
            model_name="bookmark",
            index=models.Index(fields=["owner", "-created_at", "-id"], name="idx_bookmarks_owner_created"),
        ),
        migrations.AddIndex(
            model_name="bookmark",
            index=models.Index(
                condition=Q(("status", "active"), ("visibility", "public")),
                fields=["-created_at", "-id"],
                name="idx_bookmarks_public_created",
            ),
        ),
        migrations.AddIndex(
            model_name="bookmark",
            index=django.contrib.postgres.indexes.GinIndex(fields=["tags"], name="idx_bookmarks_tags"),
        ),
        migrations.AddIndex(
            model_name="report",
            index=models.Index(fields=["status", "created_at"], name="idx_reports_status_created"),
        ),
        migrations.AddIndex(
            model_name="report",
            index=models.Index(fields=["reporter", "-created_at"], name="idx_reports_reporter_created"),
        ),
        migrations.AddIndex(
            model_name="auditentry",
            index=models.Index(fields=["-created_at"], name="idx_audit_entries_created"),
        ),
        migrations.AddConstraint(
            model_name="report",
            constraint=models.UniqueConstraint(
                condition=Q(("status", "open")),
                fields=("bookmark", "reporter"),
                name="uq_reports_one_open_per_reporter",
            ),
        ),
        migrations.AddConstraint(
            model_name="message",
            constraint=models.UniqueConstraint(fields=("key", "language"), name="uq_messages_key_language"),
        ),
    ]

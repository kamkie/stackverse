from __future__ import annotations

import json
from uuid import uuid4

from django.core.management import call_command
from django.db import connection, transaction
from django.db.migrations.executor import MigrationExecutor

from .config import config
from .logging_setup import log_event
from .models import Message
from .time import now_utc


def apply_migrations() -> None:
    executor = MigrationExecutor(connection)
    pending = [
        f"{migration.app_label}.{migration.name}"
        for migration, backwards in executor.migration_plan(executor.loader.graph.leaf_nodes())
        if not backwards
    ]
    call_command("migrate", interactive=False, verbosity=0)
    for migration in pending:
        log_event(
            "info",
            "db_migration_applied",
            "success",
            "Database migration applied",
            migration=migration,
        )


def seed_messages() -> None:
    files = sorted(config.seed_messages_dir.glob("*.json"))
    if not files:
        raise RuntimeError(
            f"Message seed directory not found: {config.seed_messages_dir} - set SEED_MESSAGES_DIR to spec/messages"
        )
    for path in files:
        language = path.stem
        entries = json.loads(path.read_text(encoding="utf-8"))
        inserted = 0
        with transaction.atomic():
            for key, text in entries.items():
                _message, created = Message.objects.get_or_create(
                    key=key,
                    language=language,
                    defaults={
                        "id": uuid4(),
                        "text": text,
                        "created_at": now_utc(),
                        "updated_at": now_utc(),
                    },
                )
                inserted += 1 if created else 0
        log_event(
            "info",
            "message_seed_imported",
            "success",
            f"Message seed '{language}': {inserted} inserted, {len(entries) - inserted} already present",
            language=language,
            inserted=inserted,
            skipped=len(entries) - inserted,
        )

from __future__ import annotations

import json
from uuid import uuid4

from .config import config
from .db import transaction
from .logging_setup import log_event
from .time import now_utc


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
        with transaction() as conn:
            for key, text in entries.items():
                result = conn.execute(
                    """
                    insert into messages (id, key, language, text, created_at, updated_at)
                    values (%s, %s, %s, %s, %s, %s)
                    on conflict (key, language) do nothing
                    """,
                    (str(uuid4()), key, language, text, now_utc(), now_utc()),
                )
                inserted += result.rowcount or 0
        log_event(
            "info",
            "message_seed_imported",
            "success",
            f"Message seed '{language}': {inserted} inserted, {len(entries) - inserted} already present",
            language=language,
            inserted=inserted,
            skipped=len(entries) - inserted,
        )

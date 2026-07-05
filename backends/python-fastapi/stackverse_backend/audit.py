from __future__ import annotations

import json
from typing import Any
from uuid import uuid4

from psycopg import Connection

from .time import now_utc


def record_audit(
    conn: Connection,
    actor: str,
    action: str,
    target_type: str,
    target_id: str,
    detail: dict[str, Any] | None = None,
) -> None:
    conn.execute(
        """
        insert into audit_entries (id, actor, action, target_type, target_id, detail, created_at)
        values (%s, %s, %s, %s, %s, %s::jsonb, %s)
        """,
        (
            str(uuid4()),
            actor,
            action,
            target_type,
            target_id,
            json.dumps(detail) if detail is not None else None,
            now_utc(),
        ),
    )

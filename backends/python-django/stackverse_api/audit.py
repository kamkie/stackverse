from __future__ import annotations

from typing import Any

from .models import AuditEntry
from .time import now_utc


def record_audit(
    actor: str, action: str, target_type: str, target_id: str, detail: dict[str, Any] | None = None
) -> None:
    AuditEntry.objects.create(
        actor=actor,
        action=action,
        target_type=target_type,
        target_id=str(target_id),
        detail=detail,
        created_at=now_utc(),
    )

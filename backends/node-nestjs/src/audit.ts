import { randomUUID } from "node:crypto";
import type { Queryable } from "./db.js";

/**
 * The append-only audit trail (SPEC rule 18): every moderator/admin mutation
 * writes an entry; there is no code path that updates or deletes one. Callers
 * inside a transaction pass their client so the entry commits atomically with
 * the change it records.
 */
export async function recordAudit(
  db: Queryable,
  actor: string,
  action: string,
  targetType: string,
  targetId: string,
  detail?: Record<string, unknown>,
): Promise<void> {
  await db.query(
    `insert into audit_entries (id, actor, action, target_type, target_id, detail, created_at)
     values ($1, $2, $3, $4, $5, $6::jsonb, $7)`,
    [randomUUID(), actor, action, targetType, targetId, detail ? JSON.stringify(detail) : null, new Date()],
  );
}

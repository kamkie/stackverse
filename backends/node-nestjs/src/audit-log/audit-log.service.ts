import { Injectable } from "@nestjs/common";
import type { FastifyRequest } from "fastify";
import { pool } from "../db.js";
import { requireRole } from "../auth.js";
import { BadRequestProblem, omitNulls, requireValidPaging, singleParam } from "../problems.js";

interface AuditRow {
  id: string;
  actor: string;
  action: string;
  target_type: string;
  target_id: string;
  detail: Record<string, unknown> | null;
  created_at: Date;
}

const toAuditResponse = (row: AuditRow) =>
  omitNulls({
    id: row.id,
    actor: row.actor,
    action: row.action,
    targetType: row.target_type,
    targetId: row.target_id,
    detail: row.detail,
    createdAt: row.created_at.toISOString(),
  });

function dateParam(value: string | undefined, name: string): Date | undefined {
  if (value === undefined) return undefined;
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) throw new BadRequestProblem(`${name} must be an RFC 3339 date-time`);
  return parsed;
}

/** Browse-only: the audit trail is append-only (SPEC rule 18) — no mutation routes exist. */
@Injectable()
export class AuditLogService {
  async list(request: FastifyRequest) {
    requireRole(request, "admin");
    const query = request.query as Record<string, unknown>;
    const { page, size } = requireValidPaging(query);

    const conditions: string[] = ["true"];
    const params: unknown[] = [];
    const bind = (value: unknown): string => {
      params.push(value);
      return `$${params.length}`;
    };
    const equals: [string, string][] = [
      ["actor", "actor"],
      ["action", "action"],
      ["targetType", "target_type"],
      ["targetId", "target_id"],
    ];
    for (const [parameter, column] of equals) {
      const value = singleParam(query[parameter], parameter);
      if (value !== undefined) conditions.push(`${column} = ${bind(value)}`);
    }
    const from = dateParam(singleParam(query["from"], "from"), "from");
    if (from !== undefined) conditions.push(`created_at >= ${bind(from)}`);
    const to = dateParam(singleParam(query["to"], "to"), "to");
    if (to !== undefined) conditions.push(`created_at <= ${bind(to)}`);
    const where = conditions.join(" and ");

    const items = await pool.query(
      `select * from audit_entries where ${where}
       order by created_at desc, id desc limit ${size} offset ${page * size}`,
      params,
    );
    const total = await pool.query(`select count(*)::int as count from audit_entries where ${where}`, params);
    const totalItems = (total.rows[0] as { count: number }).count;
    return {
      items: (items.rows as AuditRow[]).map(toAuditResponse),
      page,
      size,
      totalItems,
      totalPages: Math.ceil(totalItems / size),
    };
  }
}

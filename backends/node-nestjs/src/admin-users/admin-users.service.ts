import { Injectable } from "@nestjs/common";
import type { FastifyRequest } from "fastify";
import { pool, withTransaction } from "../db.js";
import { requireRole } from "../auth.js";
import { recordAudit } from "../audit.js";
import { logEvent } from "../logging.js";
import {
  BadRequestProblem,
  ConflictProblem,
  NotFoundProblem,
  Validator,
  escapeLike,
  omitNulls,
  requireMaxLength,
  requireValidPaging,
  singleParam,
} from "../problems.js";

interface UserAccountRow {
  username: string;
  first_seen: Date;
  last_seen: Date;
  status: "active" | "blocked";
  blocked_reason: string | null;
  bookmark_count: number;
}

const toUserAccountResponse = (row: UserAccountRow) =>
  omitNulls({
    username: row.username,
    firstSeen: row.first_seen.toISOString(),
    lastSeen: row.last_seen.toISOString(),
    status: row.status,
    blockedReason: row.blocked_reason,
    bookmarkCount: row.bookmark_count,
  });

const WITH_BOOKMARK_COUNT = `
  select u.*, (select count(*)::int from bookmarks b where b.owner = u.username) as bookmark_count
  from user_accounts u`;

async function findWithBookmarkCount(username: string): Promise<UserAccountRow | undefined> {
  const result = await pool.query(`${WITH_BOOKMARK_COUNT} where u.username = $1`, [username]);
  return result.rows[0] as UserAccountRow | undefined;
}

/** App-level accounts, lazily provisioned from JWTs — not Keycloak's user store. */
@Injectable()
export class AdminUsersService {
  async list(request: FastifyRequest) {
    requireRole(request, "admin");
    const query = request.query as Record<string, unknown>;
    const { page, size } = requireValidPaging(query);
    const q = singleParam(query["q"], "q");
    requireMaxLength(q, 100, "q");
    const status = singleParam(query["status"], "status");
    if (status !== undefined && status !== "active" && status !== "blocked") {
      throw new BadRequestProblem(`unknown status: ${status}`);
    }

    const conditions: string[] = ["true"];
    const params: unknown[] = [];
    if (q !== undefined && q.trim() !== "") {
      params.push(`%${escapeLike(q)}%`);
      conditions.push(`u.username ilike $${params.length} escape '\\'`);
    }
    if (status !== undefined) {
      params.push(status);
      conditions.push(`u.status = $${params.length}`);
    }
    const where = conditions.join(" and ");

    const items = await pool.query(
      `${WITH_BOOKMARK_COUNT} where ${where}
       order by u.last_seen desc, u.username asc limit ${size} offset ${page * size}`,
      params,
    );
    const total = await pool.query(`select count(*)::int as count from user_accounts u where ${where}`, params);
    const totalItems = (total.rows[0] as { count: number }).count;
    return {
      items: (items.rows as UserAccountRow[]).map(toUserAccountResponse),
      page,
      size,
      totalItems,
      totalPages: Math.ceil(totalItems / size),
    };
  }

  async get(request: FastifyRequest, username: string) {
    requireRole(request, "admin");
    const account = await findWithBookmarkCount(username);
    if (!account) throw new NotFoundProblem();
    return toUserAccountResponse(account);
  }

  /** SPEC rule 17: block/unblock with audit; admins cannot block themselves. */
  async setStatus(request: FastifyRequest, username: string, body: unknown) {
    const caller = requireRole(request, "admin");
    const inputBody = (typeof body === "object" && body !== null ? body : {}) as Record<string, unknown>;
    const status = inputBody["status"];
    if (status !== "active" && status !== "blocked") throw new BadRequestProblem("status is required");
    const reason = typeof inputBody["reason"] === "string" ? inputBody["reason"].trim() : undefined;

    if (status === "blocked") {
      const validator = new Validator();
      validator.check(reason !== undefined && reason !== "", "reason", "validation.block.reason.required");
      validator.check((reason?.length ?? 0) <= 1000, "reason", "validation.block.reason.too-long");
      validator.throwIfInvalid();
      if (username === caller.username) throw new ConflictProblem("Admins cannot block themselves.");
    }

    // mutation + audit commit together (SPEC rule 18: every backoffice mutation is audited)
    await withTransaction(async (client) => {
      const existing = await client.query("select username from user_accounts where username = $1 for update", [
        username,
      ]);
      if (!existing.rowCount) throw new NotFoundProblem();
      if (status === "blocked") {
        await client.query("update user_accounts set status = 'blocked', blocked_reason = $2 where username = $1", [
          username,
          reason,
        ]);
        await recordAudit(client, caller.username, "user.blocked", "user", username, { reason });
      } else {
        await client.query("update user_accounts set status = 'active', blocked_reason = null where username = $1", [
          username,
        ]);
        await recordAudit(client, caller.username, "user.unblocked", "user", username);
      }
    });
    logEvent(
      "info",
      status === "blocked" ? "user_blocked" : "user_unblocked",
      "success",
      status === "blocked" ? "User account blocked" : "User account unblocked",
      { actor: caller.username, resource_type: "user", resource_id: username },
    );
    const account = await findWithBookmarkCount(username);
    if (!account) throw new NotFoundProblem();
    return toUserAccountResponse(account);
  }
}

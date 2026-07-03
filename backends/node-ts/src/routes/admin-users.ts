import type { FastifyInstance } from "fastify";
import { pool } from "../db.js";
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
export function registerAdminUserRoutes(app: FastifyInstance): void {
  app.get("/api/v1/admin/users", async (request) => {
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
    const total = await pool.query(
      `select count(*)::int as count from user_accounts u where ${where}`,
      params,
    );
    const totalItems = (total.rows[0] as { count: number }).count;
    return {
      items: (items.rows as UserAccountRow[]).map(toUserAccountResponse),
      page,
      size,
      totalItems,
      totalPages: Math.ceil(totalItems / size),
    };
  });

  app.get("/api/v1/admin/users/:username", async (request) => {
    requireRole(request, "admin");
    const account = await findWithBookmarkCount((request.params as { username: string }).username);
    if (!account) throw new NotFoundProblem();
    return toUserAccountResponse(account);
  });

  /** SPEC rule 17: block/unblock with audit; admins cannot block themselves. */
  app.put("/api/v1/admin/users/:username/status", async (request) => {
    const caller = requireRole(request, "admin");
    const username = (request.params as { username: string }).username;
    const body = (typeof request.body === "object" && request.body !== null ? request.body : {}) as Record<
      string,
      unknown
    >;
    const status = body["status"];
    if (status !== "active" && status !== "blocked") throw new BadRequestProblem("status is required");
    const reason = typeof body["reason"] === "string" ? body["reason"].trim() : undefined;

    const existing = await pool.query("select username from user_accounts where username = $1", [username]);
    if (!existing.rowCount) throw new NotFoundProblem();

    if (status === "blocked") {
      const validator = new Validator();
      validator.check(reason !== undefined && reason !== "", "reason", "validation.block.reason.required");
      validator.check((reason?.length ?? 0) <= 1000, "reason", "validation.block.reason.too-long");
      validator.throwIfInvalid();
      if (username === caller.username) throw new ConflictProblem("Admins cannot block themselves.");
      await pool.query("update user_accounts set status = 'blocked', blocked_reason = $2 where username = $1", [
        username,
        reason,
      ]);
      await recordAudit(pool, caller.username, "user.blocked", "user", username, { reason });
      logEvent("info", "user_blocked", "success", "User account blocked", {
        actor: caller.username,
        resource_type: "user",
        resource_id: username,
      });
    } else {
      await pool.query("update user_accounts set status = 'active', blocked_reason = null where username = $1", [
        username,
      ]);
      await recordAudit(pool, caller.username, "user.unblocked", "user", username);
      logEvent("info", "user_unblocked", "success", "User account unblocked", {
        actor: caller.username,
        resource_type: "user",
        resource_id: username,
      });
    }
    const account = await findWithBookmarkCount(username);
    if (!account) throw new NotFoundProblem();
    return toUserAccountResponse(account);
  });
}

import type { FastifyInstance } from "fastify";
import { pool } from "../db.js";
import { requireRole } from "../auth.js";
import { sendWithEtag } from "../etag.js";

const DAYS = 30;
const TOP_TAGS = 10;

/** UTC calendar date as `YYYY-MM-DD`. */
const isoDate = (date: Date): string => date.toISOString().slice(0, 10);

async function countPerDay(table: string, column: string, from: Date): Promise<Map<string, number>> {
  const result = await pool.query(
    // table/column come from the two call sites below, never from input
    `select (${column} at time zone 'UTC')::date::text as day, count(*)::int as count
     from ${table} where ${column} >= $1 group by day`,
    [from],
  );
  return new Map((result.rows as { day: string; count: number }[]).map((row) => [row.day, row.count]));
}

export function registerStatsRoutes(app: FastifyInstance): void {
  /** SPEC rule 19: totals, a 30-day zero-filled daily series, top tags; ETag as for messages. */
  app.get("/api/v1/admin/stats", async (request, reply) => {
    requireRole(request, "moderator");

    const now = new Date();
    const todayStart = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate());
    const from = new Date(todayStart - (DAYS - 1) * 24 * 60 * 60 * 1000);

    const [users, bookmarks, publicBookmarks, hiddenBookmarks, openReports, createdPerDay, activePerDay, topTags] =
      await Promise.all([
        count("select count(*)::int as count from user_accounts"),
        count("select count(*)::int as count from bookmarks"),
        count("select count(*)::int as count from bookmarks where visibility = 'public'"),
        count("select count(*)::int as count from bookmarks where status = 'hidden'"),
        count("select count(*)::int as count from reports where status = 'open'"),
        countPerDay("bookmarks", "created_at", from),
        countPerDay("user_accounts", "last_seen", from),
        pool.query(
          // deterministic tie-break so identical reads hash to the same ETag
          `select tag, count(*)::int as count from bookmarks, unnest(tags) as tag
           group by tag order by count desc, tag asc limit ${TOP_TAGS}`,
        ),
      ]);

    // last 30 days including today, oldest first, zero-filled
    const daily = Array.from({ length: DAYS }, (_, offset) => {
      const date = isoDate(new Date(from.getTime() + offset * 24 * 60 * 60 * 1000));
      return {
        date,
        bookmarksCreated: createdPerDay.get(date) ?? 0,
        activeUsers: activePerDay.get(date) ?? 0,
      };
    });

    return sendWithEtag(request, reply, {
      totals: { users, bookmarks, publicBookmarks, hiddenBookmarks, openReports },
      daily,
      topTags: topTags.rows as { tag: string; count: number }[],
    });
  });
}

async function count(sql: string): Promise<number> {
  const result = await pool.query(sql);
  return (result.rows[0] as { count: number }).count;
}

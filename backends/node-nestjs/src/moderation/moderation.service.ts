import { randomUUID } from "node:crypto";
import { Injectable } from "@nestjs/common";
import type { FastifyReply, FastifyRequest } from "fastify";
import type { PoolClient } from "pg";
import { pool, withTransaction, type Queryable } from "../db.js";
import { requireCaller, requireRole } from "../auth.js";
import { recordAudit } from "../audit.js";
import { logEvent } from "../logging.js";
import { toBookmarkResponse, type BookmarkRow } from "../bookmarks/bookmarks.service.js";
import {
  BookmarkStatusBodyDto,
  ReportBodyDto,
  REPORT_STATUSES,
  ResolutionBodyDto,
  type ReportStatus,
} from "./moderation.dto.js";
import {
  BadRequestProblem,
  ConflictProblem,
  NotFoundProblem,
  omitNulls,
  parseUuid,
  requireValidPaging,
  singleParam,
} from "../problems.js";

interface ReportRow {
  id: string;
  bookmark_id: string;
  reporter: string;
  reason: string;
  comment: string | null;
  status: ReportStatus;
  resolved_by: string | null;
  resolved_at: Date | null;
  resolution_note: string | null;
  created_at: Date;
}

const toReportResponse = (row: ReportRow) =>
  omitNulls({
    id: row.id,
    bookmarkId: row.bookmark_id,
    reporter: row.reporter,
    reason: row.reason,
    comment: row.comment,
    status: row.status,
    createdAt: row.created_at.toISOString(),
    resolvedBy: row.resolved_by,
    resolvedAt: row.resolved_at?.toISOString(),
    resolutionNote: row.resolution_note,
  });

function validatedStatus(value: string | undefined): ReportStatus | undefined {
  if (value === undefined) return undefined;
  if (!(REPORT_STATUSES as readonly string[]).includes(value)) {
    throw new BadRequestProblem(`unknown status: ${value}`);
  }
  return value as ReportStatus;
}

/** Someone else's report is a 404 mask — existence is not disclosed. */
async function ownReport(db: Queryable, reporter: string, id: string): Promise<ReportRow> {
  const result = await db.query("select * from reports where id = $1 for update", [id]);
  const report = result.rows[0] as ReportRow | undefined;
  if (!report || report.reporter !== reporter) throw new NotFoundProblem();
  return report;
}

const requireOpen = (report: ReportRow): void => {
  if (report.status !== "open") throw new ConflictProblem("The report has already been resolved.");
};

async function resolveOne(
  client: PoolClient,
  report: ReportRow,
  resolution: ReportStatus,
  actor: string,
  note: string | null,
  autoResolved: boolean,
): Promise<ReportRow> {
  const result = await client.query(
    `update reports set status = $2, resolved_by = $3, resolved_at = $4, resolution_note = $5
     where id = $1 returning *`,
    [report.id, resolution, actor, new Date(), note],
  );
  await recordAudit(client, actor, "report.resolved", "report", report.id, {
    bookmarkId: report.bookmark_id,
    resolution,
    note,
    autoResolved,
  });
  logEvent("info", "report_resolved", "success", "Report resolved", {
    actor,
    resource_type: "report",
    resource_id: report.id,
    bookmark_id: report.bookmark_id,
    resolution,
    auto_resolved: autoResolved,
  });
  return result.rows[0] as ReportRow;
}

async function hideBookmark(client: PoolClient, actor: string, bookmarkId: string, note: string | null): Promise<void> {
  const result = await client.query("select * from bookmarks where id = $1", [bookmarkId]);
  const bookmark = result.rows[0] as BookmarkRow | undefined;
  if (!bookmark) throw new NotFoundProblem();
  if (bookmark.status === "hidden") return;
  await client.query("update bookmarks set status = 'hidden', updated_at = $2 where id = $1", [bookmarkId, new Date()]);
  await recordAudit(client, actor, "bookmark.status-changed", "bookmark", bookmarkId, {
    from: "active",
    to: "hidden",
    note,
  });
  logEvent("info", "bookmark_status_changed", "success", "Bookmark hidden by an actioned report", {
    actor,
    resource_type: "bookmark",
    resource_id: bookmarkId,
    from: "active",
    to: "hidden",
  });
}

@Injectable()
export class ModerationService {
  /** SPEC rule 13: only public, non-hidden bookmarks can be reported; anything else is a 404 mask. */
  async reportBookmark(
    request: FastifyRequest,
    reply: FastifyReply,
    bookmarkIdParam: string,
    input: ReportBodyDto,
  ): Promise<FastifyReply> {
    const caller = requireCaller(request);
    const bookmarkId = parseUuid(bookmarkIdParam);
    // lock the bookmark and recheck visibility inside the transaction: a
    // concurrent hide (which takes the same lock) must not let an open report
    // land on a now-hidden bookmark
    const report = await withTransaction(async (client) => {
      const found = await client.query("select visibility, status from bookmarks where id = $1 for update", [
        bookmarkId,
      ]);
      const bookmark = found.rows[0] as { visibility: string; status: string } | undefined;
      if (!bookmark || bookmark.visibility !== "public" || bookmark.status !== "active") {
        throw new NotFoundProblem();
      }
      const open = await client.query(
        "select 1 from reports where bookmark_id = $1 and reporter = $2 and status = 'open'",
        [bookmarkId, caller.username],
      );
      if (open.rowCount) throw new ConflictProblem("You already have an open report on this bookmark.");
      try {
        const created = await client.query(
          `insert into reports (id, bookmark_id, reporter, reason, comment, status, created_at)
           values ($1, $2, $3, $4, $5, 'open', $6) returning *`,
          [randomUUID(), bookmarkId, caller.username, input.reason, input.comment, new Date()],
        );
        return created.rows[0] as ReportRow;
      } catch (error) {
        // lost the race against a concurrent report by the same user — same outcome as the pre-check
        if ((error as { code?: string }).code === "23505") {
          throw new ConflictProblem("You already have an open report on this bookmark.");
        }
        throw error;
      }
    });
    logEvent("info", "report_created", "success", "Report created on a public bookmark", {
      actor: caller.username,
      resource_type: "report",
      resource_id: report.id,
      bookmark_id: bookmarkId,
      reason: report.reason,
    });
    return reply.code(201).send(toReportResponse(report));
  }

  /** SPEC rule 13: the reporter's own feedback loop — their reports, newest first. */
  async listMyReports(request: FastifyRequest) {
    const caller = requireCaller(request);
    const query = request.query as Record<string, unknown>;
    const { page, size } = requireValidPaging(query);
    const status = validatedStatus(singleParam(query["status"], "status"));

    const conditions = ["reporter = $1"];
    const params: unknown[] = [caller.username];
    if (status !== undefined) {
      params.push(status);
      conditions.push(`status = $${params.length}`);
    }
    const where = conditions.join(" and ");
    const items = await pool.query(
      `select * from reports where ${where}
       order by created_at desc, id desc limit ${size} offset ${page * size}`,
      params,
    );
    const total = await pool.query(`select count(*)::int as count from reports where ${where}`, params);
    return pageOf(items.rows as ReportRow[], page, size, (total.rows[0] as { count: number }).count);
  }

  /** SPEC rule 13: the reporter may revise reason/comment while the report is open. */
  async updateMyReport(request: FastifyRequest, idParam: string, input: ReportBodyDto) {
    const caller = requireCaller(request);
    const id = parseUuid(idParam);
    return withTransaction(async (client) => {
      const report = await ownReport(client, caller.username, id);
      requireOpen(report);
      const updated = await client.query("update reports set reason = $2, comment = $3 where id = $1 returning *", [
        id,
        input.reason,
        input.comment,
      ]);
      logEvent("info", "report_updated", "success", "Report updated by its reporter", {
        actor: caller.username,
        resource_type: "report",
        resource_id: id,
        bookmark_id: report.bookmark_id,
        reason: input.reason,
      });
      return toReportResponse(updated.rows[0] as ReportRow);
    });
  }

  /** SPEC rule 13: withdrawing removes the report and frees the one-open-report slot. */
  async withdrawReport(request: FastifyRequest, reply: FastifyReply, idParam: string): Promise<FastifyReply> {
    const caller = requireCaller(request);
    const id = parseUuid(idParam);
    await withTransaction(async (client) => {
      const report = await ownReport(client, caller.username, id);
      requireOpen(report);
      await client.query("delete from reports where id = $1", [id]);
      logEvent("info", "report_withdrawn", "success", "Report withdrawn by its reporter", {
        actor: caller.username,
        resource_type: "report",
        resource_id: id,
        bookmark_id: report.bookmark_id,
      });
    });
    return reply.code(204).send();
  }

  /** The moderation queue: open reports by default, oldest first. */
  async listAdminReports(request: FastifyRequest) {
    requireRole(request, "moderator");
    const query = request.query as Record<string, unknown>;
    const { page, size } = requireValidPaging(query);
    const status = validatedStatus(singleParam(query["status"], "status")) ?? "open";

    const items = await pool.query(
      `select * from reports where status = $1
       order by created_at asc, id asc limit ${size} offset ${page * size}`,
      [status],
    );
    const total = await pool.query("select count(*)::int as count from reports where status = $1", [status]);
    return pageOf(items.rows as ReportRow[], page, size, (total.rows[0] as { count: number }).count);
  }

  /**
   * SPEC rule 14: `actioned` hides the bookmark and drags every sibling open
   * report along. Decisions are revisable — any target status is accepted;
   * `open` re-opens the report and clears the resolution fields. Moving away
   * from `actioned` never restores the bookmark (rule 15 keeps hide/restore
   * explicit).
   *
   * Lock order: bookmark row first, then report rows. `actioned` writes the
   * bookmark *and* every sibling open report, so two moderators resolving
   * different reports of the same bookmark would otherwise lock report→bookmark
   * in opposite orders and deadlock. Taking the bookmark lock up front
   * serializes `actioned` resolutions per bookmark.
   */
  async resolveReport(request: FastifyRequest, idParam: string, input: ResolutionBodyDto) {
    const caller = requireRole(request, "moderator");
    const id = parseUuid(idParam);

    const { resolution: target, note } = input;

    return withTransaction(async (client) => {
      if (target === "actioned") {
        // bookmark_id is immutable, so an unlocked scalar read is a safe lock target;
        // a vanished bookmark cascades its reports away and the locked re-read 404s
        const scalar = await client.query("select bookmark_id from reports where id = $1", [id]);
        const bookmarkId = (scalar.rows[0] as { bookmark_id: string } | undefined)?.bookmark_id;
        if (!bookmarkId) throw new NotFoundProblem();
        await client.query("select id from bookmarks where id = $1 for update", [bookmarkId]);
      }
      const found = await client.query("select * from reports where id = $1 for update", [id]);
      const report = found.rows[0] as ReportRow | undefined;
      if (!report) throw new NotFoundProblem();

      if (target === "open") {
        // rules 13/14: at most one open report per (bookmark, reporter). Re-opening
        // while another open report exists for the same pair violates
        // uq_reports_one_open_per_reporter — a 409, not an uncaught 500. Pre-check
        // then rely on the partial unique index for races (mirrors report creation).
        const conflict = await client.query(
          "select 1 from reports where bookmark_id = $1 and reporter = $2 and status = 'open' and id <> $3",
          [report.bookmark_id, report.reporter, id],
        );
        if (conflict.rowCount) {
          throw new ConflictProblem("The reporter already has another open report on this bookmark.");
        }
        // any `note` sent with `open` is ignored — re-opening clears the resolution fields
        let reopened;
        try {
          reopened = await client.query(
            `update reports set status = 'open', resolved_by = null, resolved_at = null, resolution_note = null
             where id = $1 returning *`,
            [id],
          );
        } catch (error) {
          // lost the race against a concurrent open report by the same reporter
          if ((error as { code?: string }).code === "23505") {
            throw new ConflictProblem("The reporter already has another open report on this bookmark.");
          }
          throw error;
        }
        await recordAudit(client, caller.username, "report.reopened", "report", id, {
          bookmarkId: report.bookmark_id,
        });
        logEvent("info", "report_reopened", "success", "Report re-opened", {
          actor: caller.username,
          resource_type: "report",
          resource_id: id,
          bookmark_id: report.bookmark_id,
        });
        return toReportResponse(reopened.rows[0] as ReportRow);
      }

      const resolved = await resolveOne(client, report, target, caller.username, note, false);
      if (target === "actioned") {
        await hideBookmark(client, caller.username, report.bookmark_id, note);
        const siblings = await client.query(
          `select * from reports where bookmark_id = $1 and status = 'open' and id <> $2
           order by id asc for update`,
          [report.bookmark_id, id],
        );
        for (const sibling of siblings.rows as ReportRow[]) {
          await resolveOne(client, sibling, "actioned", caller.username, note, true);
        }
      }
      return toReportResponse(resolved);
    });
  }

  /** SPEC rule 15: hide/restore switches `status` only; `visibility` is never touched. */
  async setBookmarkStatus(request: FastifyRequest, idParam: string, input: BookmarkStatusBodyDto) {
    const caller = requireRole(request, "moderator");
    const id = parseUuid(idParam);

    const { status, note } = input;

    return withTransaction(async (client) => {
      const found = await client.query("select * from bookmarks where id = $1 for update", [id]);
      const bookmark = found.rows[0] as BookmarkRow | undefined;
      if (!bookmark) throw new NotFoundProblem();
      const updated = await client.query(
        "update bookmarks set status = $2, updated_at = $3 where id = $1 returning *",
        [id, status, new Date()],
      );
      await recordAudit(client, caller.username, "bookmark.status-changed", "bookmark", id, {
        from: bookmark.status,
        to: status,
        note,
      });
      logEvent("info", "bookmark_status_changed", "success", "Bookmark moderation status changed", {
        actor: caller.username,
        resource_type: "bookmark",
        resource_id: id,
        from: bookmark.status,
        to: status,
      });
      return toBookmarkResponse(updated.rows[0] as BookmarkRow);
    });
  }
}

function pageOf(rows: ReportRow[], page: number, size: number, totalItems: number) {
  return {
    items: rows.map(toReportResponse),
    page,
    size,
    totalItems,
    totalPages: Math.ceil(totalItems / size),
  };
}

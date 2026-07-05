import { randomUUID } from "node:crypto";
import { Injectable } from "@nestjs/common";
import type { FastifyReply, FastifyRequest } from "fastify";
import { pool, withTransaction, type Queryable } from "../db.js";
import { encodeCursor, decodeCursor, type BookmarkCursor } from "../cursor.js";
import { requireCaller } from "../auth.js";
import {
  BadRequestProblem,
  ConflictProblem,
  NotFoundProblem,
  UnauthorizedProblem,
  Validator,
  escapeLike,
  multiParam,
  omitNulls,
  parseUuid,
  requireMaxLength,
  requireValidPaging,
  singleParam,
} from "../problems.js";

/**
 * The v1 bookmarks listing is a permanent deprecation exhibit (see docs/SPEC.md):
 * deprecated 2026-07-01, nominal sunset 2027-07-01, succeeded by /api/v2/bookmarks.
 */
const V1_BOOKMARKS_DEPRECATION = "@1782864000";
const V1_BOOKMARKS_SUNSET = "Thu, 01 Jul 2027 00:00:00 GMT";
const V1_BOOKMARKS_SUCCESSOR = '</api/v2/bookmarks>; rel="successor-version"';

const TAG_PATTERN = /^[a-z0-9-]{1,30}$/;
const VISIBILITIES = ["private", "public"] as const;
type Visibility = (typeof VISIBILITIES)[number];

export interface BookmarkRow {
  id: string;
  owner: string;
  url: string;
  title: string;
  notes: string | null;
  tags: string[];
  visibility: Visibility;
  status: "active" | "hidden";
  created_at: Date;
  updated_at: Date;
}

export const toBookmarkResponse = (row: BookmarkRow) =>
  omitNulls({
    id: row.id,
    url: row.url,
    title: row.title,
    notes: row.notes,
    tags: row.tags,
    visibility: row.visibility,
    status: row.status,
    owner: row.owner,
    createdAt: row.created_at.toISOString(),
    updatedAt: row.updated_at.toISOString(),
  });

interface BookmarkInput {
  url: string;
  title: string;
  notes: string | null;
  tags: string[];
  visibility: Visibility;
}

const isHttpUrl = (value: string): boolean => {
  try {
    const url = new URL(value);
    return (url.protocol === "http:" || url.protocol === "https:") && url.hostname.length > 0;
  } catch {
    return false;
  }
};

/** SPEC rules 5 + 11: all field errors collected into one problem document. */
export function validateBookmarkInput(body: unknown): BookmarkInput {
  const input = (typeof body === "object" && body !== null ? body : {}) as Record<string, unknown>;
  const validator = new Validator();

  const url = typeof input["url"] === "string" ? input["url"].trim() : "";
  if (url === "") {
    validator.reject("url", "validation.url.required");
  } else {
    validator.check(url.length <= 2000 && isHttpUrl(url), "url", "validation.url.invalid");
  }

  const title = typeof input["title"] === "string" ? input["title"].trim() : "";
  validator.check(title !== "", "title", "validation.title.required");
  validator.check(title.length <= 200, "title", "validation.title.too-long");

  const notes = typeof input["notes"] === "string" ? input["notes"] : null;
  validator.check((notes?.length ?? 0) <= 4000, "notes", "validation.notes.too-long");

  // normalized before validation: " Kotlin " and "kotlin" are the same tag
  const rawTags = Array.isArray(input["tags"]) ? input["tags"] : [];
  const tags = [...new Set(rawTags.map((tag) => String(tag).trim().toLowerCase()))];
  validator.check(tags.length <= 10, "tags", "validation.tags.too-many");
  validator.check(
    tags.every((tag) => TAG_PATTERN.test(tag)),
    "tags",
    "validation.tag.invalid",
  );

  const visibility = input["visibility"] ?? "private";
  if (!VISIBILITIES.includes(visibility as Visibility)) {
    throw new BadRequestProblem(`unknown visibility: ${String(visibility)}`);
  }

  validator.throwIfInvalid();
  return { url, title, notes, tags, visibility: visibility as Visibility };
}

interface ListFilters {
  tags: string[];
  q?: string;
  visibility?: Visibility;
}

export function validateQueryTags(rawTags: string[]): string[] {
  const tags = rawTags.map((tag) => tag.trim().toLowerCase());
  const validator = new Validator();
  validator.check(
    tags.every((tag) => TAG_PATTERN.test(tag)),
    "tag",
    "validation.tag.invalid",
  );
  validator.throwIfInvalid();
  return tags;
}

function parseListFilters(query: Record<string, unknown>): ListFilters {
  const q = singleParam(query["q"], "q");
  requireMaxLength(q, 200, "q");
  const visibility = singleParam(query["visibility"], "visibility");
  if (visibility !== undefined && !VISIBILITIES.includes(visibility as Visibility)) {
    throw new BadRequestProblem(`unknown visibility: ${visibility}`);
  }
  return {
    tags: validateQueryTags(multiParam(query["tag"])),
    ...(q !== undefined && { q }),
    ...(visibility !== undefined && { visibility: visibility as Visibility }),
  };
}

/**
 * Rule 2 + 3: `visibility=public` is the anonymous-capable public feed across
 * all owners (hidden excluded); every other listing is the caller's own
 * bookmarks. `id` breaks `created_at` ties so the order is total — a keyset
 * requirement.
 */
function listingWhere(caller: string | null, filters: ListFilters): { where: string; params: unknown[] } {
  const conditions: string[] = [];
  const params: unknown[] = [];
  const bind = (value: unknown): string => {
    params.push(value);
    return `$${params.length}`;
  };

  if (filters.visibility === "public") {
    conditions.push("visibility = 'public' and status = 'active'");
  } else {
    if (caller === null) throw new UnauthorizedProblem();
    conditions.push(`owner = ${bind(caller)}`);
    if (filters.visibility !== undefined) conditions.push(`visibility = ${bind(filters.visibility)}`);
  }
  if (filters.tags.length > 0) {
    conditions.push(`tags @> ${bind(filters.tags)}::text[]`);
  }
  if (filters.q !== undefined && filters.q.trim() !== "") {
    const pattern = bind(`%${escapeLike(filters.q)}%`);
    conditions.push(`(title ilike ${pattern} escape '\\' or notes ilike ${pattern} escape '\\')`);
  }
  return { where: conditions.join(" and "), params };
}

async function findBookmark(db: Queryable, id: string): Promise<BookmarkRow | undefined> {
  const result = await db.query("select * from bookmarks where id = $1", [id]);
  return result.rows[0] as BookmarkRow | undefined;
}

/** Rule 1: a non-owner never learns the bookmark exists — 404, not 403. */
async function ownedByCaller(caller: string, id: string): Promise<BookmarkRow> {
  const bookmark = await findBookmark(pool, id);
  if (!bookmark || bookmark.owner !== caller) throw new NotFoundProblem();
  return bookmark;
}

const isVisibleTo = (bookmark: BookmarkRow, caller: string | null): boolean =>
  bookmark.owner === caller || (bookmark.visibility === "public" && bookmark.status === "active");

@Injectable()
export class BookmarksService {
  /** RFC 9745 / 8594 / 8288 deprecation signaling on every v1 listing response. */
  async listV1(request: FastifyRequest, reply: FastifyReply): Promise<Record<string, unknown>> {
    reply
      .header("deprecation", V1_BOOKMARKS_DEPRECATION)
      .header("sunset", V1_BOOKMARKS_SUNSET)
      .header("link", V1_BOOKMARKS_SUCCESSOR);
    const query = request.query as Record<string, unknown>;
    const { page, size } = requireValidPaging(query);
    const filters = parseListFilters(query);
    const { where, params } = listingWhere(request.caller?.username ?? null, filters);

    const items = await pool.query(
      `select * from bookmarks where ${where}
       order by created_at desc, id desc
       limit ${size} offset ${page * size}`,
      params,
    );
    const total = await pool.query(`select count(*)::int as count from bookmarks where ${where}`, params);
    const totalItems = (total.rows[0] as { count: number }).count;
    return {
      items: (items.rows as BookmarkRow[]).map(toBookmarkResponse),
      page,
      size,
      totalItems,
      totalPages: Math.ceil(totalItems / size),
    };
  }

  /** Successor of the v1 listing: same filters and semantics, keyset pagination. */
  async listV2(request: FastifyRequest): Promise<Record<string, unknown>> {
    const query = request.query as Record<string, unknown>;
    const { size } = requireValidPaging(query);
    const filters = parseListFilters(query);
    const rawCursor = singleParam(query["cursor"], "cursor");
    const cursor: BookmarkCursor | undefined = rawCursor === undefined ? undefined : decodeCursor(rawCursor);
    const { where, params } = listingWhere(request.caller?.username ?? null, filters);

    let cursorCondition = "";
    if (cursor) {
      const created = `$${params.length + 1}`;
      const id = `$${params.length + 2}`;
      params.push(cursor.createdAt, cursor.id);
      cursorCondition = ` and (created_at < ${created} or (created_at = ${created} and id < ${id}))`;
    }
    const result = await pool.query(
      `select * from bookmarks where ${where}${cursorCondition}
       order by created_at desc, id desc
       limit ${size + 1}`,
      params,
    );
    const fetched = result.rows as BookmarkRow[];
    const items = fetched.slice(0, size);
    const last = items[items.length - 1];
    return {
      items: items.map(toBookmarkResponse),
      ...(fetched.length > size &&
        last !== undefined && { nextCursor: encodeCursor({ createdAt: last.created_at, id: last.id }) }),
    };
  }

  async create(request: FastifyRequest, reply: FastifyReply, body: unknown): Promise<FastifyReply> {
    const caller = requireCaller(request);
    const input = validateBookmarkInput(body);
    const now = new Date();
    const id = randomUUID();
    const result = await pool.query(
      `insert into bookmarks (id, owner, url, title, notes, tags, visibility, status, created_at, updated_at)
       values ($1, $2, $3, $4, $5, $6::text[], $7, 'active', $8, $8)
       returning *`,
      [id, caller.username, input.url, input.title, input.notes, input.tags, input.visibility, now],
    );
    return reply
      .code(201)
      .header("location", `/api/v1/bookmarks/${id}`)
      .send(toBookmarkResponse(result.rows[0] as BookmarkRow));
  }

  async get(request: FastifyRequest, idParam: string): Promise<Partial<BookmarkRow>> {
    const id = parseUuid(idParam);
    const bookmark = await findBookmark(pool, id);
    if (!bookmark || !isVisibleTo(bookmark, request.caller?.username ?? null)) {
      throw new NotFoundProblem();
    }
    return toBookmarkResponse(bookmark);
  }

  async update(request: FastifyRequest, idParam: string, body: unknown): Promise<Partial<BookmarkRow>> {
    const caller = requireCaller(request);
    const id = parseUuid(idParam);
    const input = validateBookmarkInput(body);
    // lock the row so a concurrent moderation hide cannot slip between the
    // hidden-publish check and the write (SPEC rule 15) — moderation takes the
    // same `for update` lock, so the two serialize
    const updated = await withTransaction(async (client) => {
      const found = await client.query("select * from bookmarks where id = $1 for update", [id]);
      const bookmark = found.rows[0] as BookmarkRow | undefined;
      if (!bookmark || bookmark.owner !== caller.username) throw new NotFoundProblem();
      if (bookmark.status === "hidden" && input.visibility === "public") {
        throw new ConflictProblem(
          "This bookmark was hidden by moderation and cannot be made public.",
          "error.bookmark.hidden-publish",
        );
      }
      const result = await client.query(
        `update bookmarks set url = $2, title = $3, notes = $4, tags = $5::text[], visibility = $6, updated_at = $7
         where id = $1 returning *`,
        [id, input.url, input.title, input.notes, input.tags, input.visibility, new Date()],
      );
      return result.rows[0] as BookmarkRow;
    });
    return toBookmarkResponse(updated);
  }

  async delete(request: FastifyRequest, reply: FastifyReply, idParam: string): Promise<FastifyReply> {
    const caller = requireCaller(request);
    const id = parseUuid(idParam);
    await ownedByCaller(caller.username, id);
    await pool.query("delete from bookmarks where id = $1", [id]);
    return reply.code(204).send();
  }

  /** SPEC rule 4: the caller's tags with usage counts, most used first. */
  async listTags(request: FastifyRequest): Promise<{ tags: { tag: string; count: number }[] }> {
    const caller = requireCaller(request);
    const result = await pool.query(
      `select tag, count(*)::int as count
       from bookmarks, unnest(tags) as tag
       where owner = $1
       group by tag
       order by count desc, tag asc`,
      [caller.username],
    );
    return { tags: result.rows as { tag: string; count: number }[] };
  }
}

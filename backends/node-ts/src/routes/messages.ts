import { randomUUID } from "node:crypto";
import type { FastifyInstance, FastifyRequest } from "fastify";
import { pool, withTransaction } from "../db.js";
import { requireRole } from "../auth.js";
import { recordAudit } from "../audit.js";
import { logEvent } from "../logging.js";
import { sendWithEtag } from "../etag.js";
import { DEFAULT_LANGUAGE, messageBundle, resolveLanguage } from "../i18n.js";
import {
  ConflictProblem,
  NotFoundProblem,
  Validator,
  escapeLike,
  firstParam,
  omitNulls,
  parseUuid,
  requireMaxLength,
  requireValidPaging,
  singleParam,
} from "../problems.js";

const KEY_PATTERN = /^[a-z0-9-]+(\.[a-z0-9-]+)*$/;
const LANGUAGE_PATTERN = /^[a-z]{2}$/;

interface MessageRow {
  id: string;
  key: string;
  language: string;
  text: string;
  description: string | null;
  created_at: Date;
  updated_at: Date;
}

const toMessageResponse = (row: MessageRow) =>
  omitNulls({
    id: row.id,
    key: row.key,
    language: row.language,
    text: row.text,
    description: row.description,
    createdAt: row.created_at.toISOString(),
    updatedAt: row.updated_at.toISOString(),
  });

interface MessageInput {
  key: string;
  language: string;
  text: string;
  description: string | null;
}

function validateMessageInput(body: unknown): MessageInput {
  const input = (typeof body === "object" && body !== null ? body : {}) as Record<string, unknown>;
  const validator = new Validator();

  const key = typeof input["key"] === "string" ? input["key"].trim() : "";
  validator.check(KEY_PATTERN.test(key) && key.length <= 150, "key", "validation.message.key.invalid");

  const language = typeof input["language"] === "string" ? input["language"].trim() : "";
  validator.check(LANGUAGE_PATTERN.test(language), "language", "validation.message.language.invalid");

  const text = typeof input["text"] === "string" ? input["text"] : "";
  validator.check(text !== "", "text", "validation.message.text.required");
  validator.check(text.length <= 2000, "text", "validation.message.text.too-long");

  const description = typeof input["description"] === "string" ? input["description"] : null;
  validator.check((description?.length ?? 0) <= 1000, "description", "validation.message.description.too-long");

  validator.throwIfInvalid();
  return { key, language, text, description };
}

/** The message key is safe to log: validated against `KEY_PATTERN`, so no free-form client text. */
function logMessageEvent(event: string, description: string, actor: string, message: MessageRow): void {
  logEvent("info", event, "success", description, {
    actor,
    resource_type: "message",
    resource_id: message.id,
    message_key: message.key,
    language: message.language,
  });
}

async function requestLanguage(request: FastifyRequest): Promise<string> {
  return resolveLanguage(
    firstParam((request.query as Record<string, unknown>)["lang"]),
    request.headers["accept-language"],
  );
}

export function registerMessageRoutes(app: FastifyInstance): void {
  /** Reads are public with ETag revalidation (SPEC rules 7 + 10). */
  app.get("/api/v1/messages", async (request, reply) => {
    const query = request.query as Record<string, unknown>;
    const { page, size } = requireValidPaging(query);
    const key = singleParam(query["key"], "key");
    const language = singleParam(query["language"], "language");
    const q = singleParam(query["q"], "q");
    requireMaxLength(q, 200, "q");

    const conditions: string[] = ["true"];
    const params: unknown[] = [];
    const bind = (value: unknown): string => {
      params.push(value);
      return `$${params.length}`;
    };
    if (key !== undefined) conditions.push(`key = ${bind(key)}`);
    if (language !== undefined) conditions.push(`language = ${bind(language)}`);
    if (q !== undefined && q.trim() !== "") {
      const pattern = bind(`%${escapeLike(q)}%`);
      conditions.push(`(key ilike ${pattern} escape '\\' or text ilike ${pattern} escape '\\')`);
    }
    const where = conditions.join(" and ");

    // ordered so identical reads produce identical bodies — the ETag depends on it
    const items = await pool.query(
      `select * from messages where ${where} order by key, language limit ${size} offset ${page * size}`,
      params,
    );
    const total = await pool.query(`select count(*)::int as count from messages where ${where}`, params);
    const totalItems = (total.rows[0] as { count: number }).count;
    return sendWithEtag(request, reply, {
      items: (items.rows as MessageRow[]).map(toMessageResponse),
      page,
      size,
      totalItems,
      totalPages: Math.ceil(totalItems / size),
    });
  });

  /** The flat key → text bundle frontends load on startup (SPEC rule 9). */
  app.get("/api/v1/messages/bundle", async (request, reply) => {
    const language = await requestLanguage(request);
    reply.header("content-language", language);
    return sendWithEtag(request, reply, { language, messages: await messageBundle(language) });
  });

  app.get("/api/v1/messages/:id", async (request, reply) => {
    const id = parseUuid((request.params as { id: string }).id);
    const result = await pool.query("select * from messages where id = $1", [id]);
    const message = result.rows[0] as MessageRow | undefined;
    if (!message) throw new NotFoundProblem();
    return sendWithEtag(request, reply, toMessageResponse(message));
  });

  app.post("/api/v1/messages", async (request, reply) => {
    const caller = requireRole(request, "admin");
    const input = validateMessageInput(request.body);
    // mutation + audit commit together (SPEC rule 18: every backoffice mutation is audited)
    const message = await withTransaction(async (client) => {
      const duplicate = await client.query("select 1 from messages where key = $1 and language = $2", [
        input.key,
        input.language,
      ]);
      if (duplicate.rowCount) throw duplicateConflict(input);
      let row: MessageRow;
      try {
        const result = await client.query(
          `insert into messages (id, key, language, text, description, created_at, updated_at)
           values ($1, $2, $3, $4, $5, $6, $6) returning *`,
          [randomUUID(), input.key, input.language, input.text, input.description, new Date()],
        );
        row = result.rows[0] as MessageRow;
      } catch (error) {
        // lost the (key, language) race against a concurrent create — same 409 as the pre-check
        if ((error as { code?: string }).code === "23505") throw duplicateConflict(input);
        throw error;
      }
      await recordAudit(client, caller.username, "message.created", "message", row.id, snapshot(row));
      return row;
    });
    logMessageEvent("message_created", "Message created", caller.username, message);
    return reply.code(201).header("location", `/api/v1/messages/${message.id}`).send(toMessageResponse(message));
  });

  app.put("/api/v1/messages/:id", async (request) => {
    const caller = requireRole(request, "admin");
    const id = parseUuid((request.params as { id: string }).id);
    const input = validateMessageInput(request.body);
    const message = await withTransaction(async (client) => {
      const existing = await client.query("select 1 from messages where id = $1", [id]);
      if (!existing.rowCount) throw new NotFoundProblem();
      const duplicate = await client.query("select 1 from messages where key = $1 and language = $2 and id <> $3", [
        input.key,
        input.language,
        id,
      ]);
      if (duplicate.rowCount) throw duplicateConflict(input);
      let row: MessageRow;
      try {
        const result = await client.query(
          `update messages set key = $2, language = $3, text = $4, description = $5, updated_at = $6
           where id = $1 returning *`,
          [id, input.key, input.language, input.text, input.description, new Date()],
        );
        row = result.rows[0] as MessageRow;
      } catch (error) {
        if ((error as { code?: string }).code === "23505") throw duplicateConflict(input);
        throw error;
      }
      await recordAudit(client, caller.username, "message.updated", "message", row.id, snapshot(row));
      return row;
    });
    logMessageEvent("message_updated", "Message updated", caller.username, message);
    return toMessageResponse(message);
  });

  app.delete("/api/v1/messages/:id", async (request, reply) => {
    const caller = requireRole(request, "admin");
    const id = parseUuid((request.params as { id: string }).id);
    const message = await withTransaction(async (client) => {
      const result = await client.query("delete from messages where id = $1 returning *", [id]);
      const row = result.rows[0] as MessageRow | undefined;
      if (!row) throw new NotFoundProblem();
      await recordAudit(client, caller.username, "message.deleted", "message", row.id, snapshot(row));
      return row;
    });
    logMessageEvent("message_deleted", "Message deleted", caller.username, message);
    return reply.code(204).send();
  });
}

const duplicateConflict = (input: { key: string; language: string }): ConflictProblem =>
  new ConflictProblem(`A message with key '${input.key}' and language '${input.language}' already exists.`);

const snapshot = (message: MessageRow): Record<string, unknown> => ({
  key: message.key,
  language: message.language,
  text: message.text,
  description: message.description,
});

export { DEFAULT_LANGUAGE };

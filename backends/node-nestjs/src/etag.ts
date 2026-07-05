import { createHash } from "node:crypto";
import type { FastifyReply, FastifyRequest } from "fastify";

/**
 * ETag / `If-None-Match` / `304` for message reads and stats (SPEC rules 10 + 19).
 * Hashing the response body is what keeps this stateless: any write changes the
 * body, hence the ETag — with no version counter to coordinate between instances.
 */
export function sendWithEtag(request: FastifyRequest, reply: FastifyReply, payload: unknown): FastifyReply {
  const body = JSON.stringify(payload);
  const etag = `"${createHash("sha256").update(body).digest("base64url")}"`;
  reply.header("etag", etag).header("cache-control", "no-cache");
  const ifNoneMatch = request.headers["if-none-match"];
  if (ifNoneMatch !== undefined && ifNoneMatch.split(",").some((tag) => tag.trim() === etag)) {
    return reply.code(304).send();
  }
  return reply.type("application/json; charset=utf-8").send(body);
}

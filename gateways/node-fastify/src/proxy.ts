import { context, propagation } from "@opentelemetry/api";
import type { FastifyReply, FastifyRequest } from "fastify";
import { logEvent } from "./logging.js";

export type FetchLike = typeof fetch;

const HOP_BY_HOP = new Set([
  "connection",
  "keep-alive",
  "proxy-authenticate",
  "proxy-authorization",
  "te",
  "trailer",
  "transfer-encoding",
  "upgrade",
]);

const STRIPPED_REQUEST_HEADERS = new Set([
  "authorization",
  "cookie",
  "host",
  "x-xsrf-token",
  "content-length",
]);

const STRIPPED_RESPONSE_HEADERS = new Set([
  "connection",
  "keep-alive",
  "transfer-encoding",
  "upgrade",
]);

export async function proxyRequest(
  request: FastifyRequest,
  reply: FastifyReply,
  targetBase: URL,
  dependency: "backend" | "frontend",
  fetchImpl: FetchLike,
  accessToken?: string,
): Promise<FastifyReply> {
  const upstreamUrl = new URL(request.url, targetBase);
  const headers = new Headers();
  for (const [name, rawValue] of Object.entries(request.headers)) {
    const lower = name.toLowerCase();
    if (HOP_BY_HOP.has(lower) || STRIPPED_REQUEST_HEADERS.has(lower)) continue;
    if (Array.isArray(rawValue)) {
      for (const value of rawValue) headers.append(name, value);
    } else if (rawValue !== undefined) {
      headers.set(name, String(rawValue));
    }
  }
  if (accessToken) {
    headers.set("authorization", `Bearer ${accessToken}`);
  }
  propagation.inject(context.active(), headers, {
    set(carrier, key, value) {
      carrier.set(key, value);
    },
  });

  const init: RequestInit & { duplex?: "half" } = {
    method: request.method,
    headers,
    redirect: "manual",
  };
  if (request.method !== "GET" && request.method !== "HEAD") {
    const body = request.body;
    if (Buffer.isBuffer(body)) {
      init.body = body;
      init.duplex = "half";
    } else if (body !== undefined && body !== null) {
      init.body = Buffer.from(String(body));
      init.duplex = "half";
    }
  }

  const started = Date.now();
  let upstream: Response;
  try {
    upstream = await fetchImpl(upstreamUrl, init);
  } catch (error) {
    logEvent("error", "dependency_call_failed", "failure", `${dependency} upstream request failed`, {
      dependency,
      duration_ms: Date.now() - started,
      error_code: error instanceof Error ? error.name : "fetch_failed",
    });
    return reply.code(502).type("application/problem+json").send({
      type: "about:blank",
      title: "Bad Gateway",
      status: 502,
      detail: "The upstream service is unavailable.",
    });
  }

  reply.code(upstream.status);
  upstream.headers.forEach((value, name) => {
    const lower = name.toLowerCase();
    if (!STRIPPED_RESPONSE_HEADERS.has(lower)) {
      reply.header(name, value);
    }
  });

  if (upstream.status === 204 || upstream.status === 304 || request.method === "HEAD") {
    return reply.send();
  }
  return reply.send(Buffer.from(await upstream.arrayBuffer()));
}

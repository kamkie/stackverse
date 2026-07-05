import { context, propagation } from "@opentelemetry/api";
import type { FastifyReply, FastifyRequest } from "fastify";
import type { IncomingHttpHeaders } from "node:http";
import { logEvent } from "./logging.js";

const STRIPPED_REQUEST_HEADERS = new Set([
  "authorization",
  "cookie",
  "keep-alive",
  "proxy-authenticate",
  "proxy-authorization",
  "te",
  "trailer",
  "upgrade",
  "x-xsrf-token",
]);

export async function proxyRequest(
  request: FastifyRequest,
  reply: FastifyReply,
  targetBase: URL,
  dependency: "backend" | "frontend",
  accessToken?: string,
): Promise<FastifyReply> {
  const upstreamUrl = new URL(request.url, targetBase);
  const started = Date.now();
  return reply.from(upstreamUrl.toString(), {
    rewriteRequestHeaders(_proxyRequest, headers) {
      const rewritten = stripGatewayRequestHeaders(headers);
      if (accessToken) {
        rewritten.authorization = `Bearer ${accessToken}`;
      }
      propagation.inject(context.active(), rewritten, {
        set(carrier, key, value) {
          carrier[key] = value;
        },
      });
      return rewritten;
    },
    onError(proxyReply, { error }) {
      logEvent("error", "dependency_call_failed", "failure", `${dependency} upstream request failed`, {
        dependency,
        duration_ms: Date.now() - started,
        error_code: error instanceof Error ? error.name : "proxy_failed",
      });
      return proxyReply.code(502).type("application/problem+json").send({
        type: "about:blank",
        title: "Bad Gateway",
        status: 502,
        detail: "The upstream service is unavailable.",
      });
    },
  });
}

function stripGatewayRequestHeaders(headers: IncomingHttpHeaders): IncomingHttpHeaders {
  const rewritten: IncomingHttpHeaders = { ...headers };
  for (const name of STRIPPED_REQUEST_HEADERS) {
    delete rewritten[name];
  }
  return rewritten;
}

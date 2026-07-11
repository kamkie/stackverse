import { randomBytes, timingSafeEqual } from "node:crypto";
import type { FastifyReply, FastifyRequest } from "fastify";

export const XSRF_COOKIE = "XSRF-TOKEN";
export const XSRF_HEADER = "x-xsrf-token";
export const SESSION_COOKIE = "stackverse_session";

export const CONTENT_SECURITY_POLICY = "default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'";
export const STRICT_TRANSPORT_SECURITY = "max-age=31536000; includeSubDomains";

const STATE_CHANGING = new Set(["POST", "PUT", "PATCH", "DELETE"]);

export function randomToken(bytes = 32): string {
  return randomBytes(bytes).toString("base64url");
}

export function issueCsrfCookie(request: FastifyRequest, reply: FastifyReply, secure: boolean): void {
  if (request.cookies[XSRF_COOKIE]) return;
  reply.setCookie(XSRF_COOKIE, randomToken(16), {
    httpOnly: false,
    sameSite: "lax",
    secure,
    path: "/",
  });
}

export function hasValidCsrf(request: FastifyRequest): boolean {
  if (!STATE_CHANGING.has(request.method.toUpperCase())) return true;

  const cookie = request.cookies[XSRF_COOKIE];
  const header = headerValue(request, XSRF_HEADER);
  if (!cookie || !header) return false;

  const cookieBytes = Buffer.from(cookie, "utf8");
  const headerBytes = Buffer.from(header, "utf8");
  return cookieBytes.length === headerBytes.length && timingSafeEqual(cookieBytes, headerBytes);
}

export function isSameOriginStateChange(request: FastifyRequest, expectedOrigin: string): boolean {
  if (!request.url.startsWith("/api") || !STATE_CHANGING.has(request.method.toUpperCase())) {
    return true;
  }

  const origin = headerValue(request, "origin");
  if (origin && canonicalOriginOrNull(origin) !== expectedOrigin) {
    return false;
  }

  const fetchSite = headerValue(request, "sec-fetch-site");
  return !fetchSite || fetchSite.toLowerCase() === "same-origin" || fetchSite.toLowerCase() === "none";
}

export function canonicalPublicOrigin(publicUrl: URL): string {
  return canonicalOrigin(publicUrl);
}

export function applySecurityHeaders(request: FastifyRequest, reply: FastifyReply, httpsPublicMode: boolean): void {
  const apiResponse = request.url.startsWith("/api");
  reply.header("X-Content-Type-Options", "nosniff");
  if (httpsPublicMode) {
    reply.header("Strict-Transport-Security", STRICT_TRANSPORT_SECURITY);
  }

  if (apiResponse) return;

  reply.header("Referrer-Policy", "same-origin");
  reply.header("Content-Security-Policy", CONTENT_SECURITY_POLICY);
  reply.header("X-Frame-Options", "DENY");
  reply.header("Cross-Origin-Opener-Policy", "same-origin");
  reply.header("Cross-Origin-Resource-Policy", "same-origin");
}

export function headerValue(request: FastifyRequest, name: string): string | undefined {
  const value = request.headers[name.toLowerCase()];
  if (Array.isArray(value)) return value[0];
  return value;
}

function canonicalOriginOrNull(value: string): string | null {
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    return null;
  }
  if (url.search || url.hash || value.endsWith("/")) {
    return null;
  }
  const origin = canonicalOrigin(url);
  return value === origin ? origin : null;
}

function canonicalOrigin(url: URL): string {
  if (!url.protocol || !url.hostname) {
    throw new Error("PUBLIC_URL must include a scheme and host.");
  }
  const scheme = url.protocol.slice(0, -1).toLowerCase();
  let host = url.hostname.toLowerCase();
  if (host.includes(":") && !host.startsWith("[")) {
    host = `[${host}]`;
  }
  const defaultPort =
    (scheme === "http" && (url.port === "" || url.port === "80")) ||
    (scheme === "https" && (url.port === "" || url.port === "443"));
  return `${scheme}://${host}${defaultPort ? "" : `:${url.port}`}`;
}

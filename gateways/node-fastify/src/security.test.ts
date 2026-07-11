import type { FastifyReply, FastifyRequest } from "fastify";
import { describe, expect, it, vi } from "vitest";
import {
  CONTENT_SECURITY_POLICY,
  STRICT_TRANSPORT_SECURITY,
  XSRF_COOKIE,
  XSRF_HEADER,
  applySecurityHeaders,
  canonicalPublicOrigin,
  hasValidCsrf,
  headerValue,
  isSameOriginStateChange,
  issueCsrfCookie,
} from "./security.js";

function request(
  method: string,
  url: string,
  headers: Record<string, string | string[] | undefined> = {},
  cookies: Record<string, string> = {},
): FastifyRequest {
  return { method, url, headers, cookies } as unknown as FastifyRequest;
}

describe("gateway security policy", () => {
  it("uses a constant-time double-submit comparison without requiring CSRF on safe methods", () => {
    expect(hasValidCsrf(request("GET", "/api/v1/bookmarks"))).toBe(true);
    expect(hasValidCsrf(request("POST", "/api/v1/bookmarks"))).toBe(false);
    expect(
      hasValidCsrf(
        request("POST", "/api/v1/bookmarks", { [XSRF_HEADER]: "matching-token" }, { [XSRF_COOKIE]: "matching-token" }),
      ),
    ).toBe(true);
    expect(
      hasValidCsrf(request("DELETE", "/api/v1/bookmarks/id", { [XSRF_HEADER]: "short" }, { [XSRF_COOKIE]: "longer" })),
    ).toBe(false);
    expect(
      hasValidCsrf(
        request("PATCH", "/api/v1/bookmarks/id", { [XSRF_HEADER]: "token-b" }, { [XSRF_COOKIE]: "token-a" }),
      ),
    ).toBe(false);
  });

  it("issues one readable same-site CSRF cookie and preserves an existing browser token", () => {
    const setCookie = vi.fn();
    const reply = { setCookie } as unknown as FastifyReply;

    issueCsrfCookie(request("GET", "/", {}, { [XSRF_COOKIE]: "already-issued" }), reply, true);
    expect(setCookie).not.toHaveBeenCalled();

    issueCsrfCookie(request("GET", "/"), reply, true);
    expect(setCookie).toHaveBeenCalledOnce();
    expect(setCookie).toHaveBeenCalledWith(XSRF_COOKIE, expect.stringMatching(/^[A-Za-z0-9_-]{22}$/), {
      httpOnly: false,
      sameSite: "lax",
      secure: true,
      path: "/",
    });
  });

  it("normalizes the configured public origin while preserving non-default and IPv6 ports", () => {
    expect(canonicalPublicOrigin(new URL("https://STACKVERSE.example:443/app"))).toBe("https://stackverse.example");
    expect(canonicalPublicOrigin(new URL("http://stackverse.example:8080/app"))).toBe("http://stackverse.example:8080");
    expect(canonicalPublicOrigin(new URL("http://[2001:db8::1]:8000/app"))).toBe("http://[2001:db8::1]:8000");
    expect(() => canonicalPublicOrigin(new URL("mailto:demo@example.com"))).toThrow(
      "PUBLIC_URL must include a scheme and host",
    );
  });

  it("accepts only canonical same-origin browser signals for state changes", () => {
    const expectedOrigin = "http://localhost:8000";

    expect(isSameOriginStateChange(request("GET", "/api/v1/bookmarks"), expectedOrigin)).toBe(true);
    expect(isSameOriginStateChange(request("POST", "/auth/logout"), expectedOrigin)).toBe(true);
    expect(
      isSameOriginStateChange(
        request("POST", "/api/v1/bookmarks", { origin: expectedOrigin, "sec-fetch-site": "NoNe" }),
        expectedOrigin,
      ),
    ).toBe(true);
    expect(
      isSameOriginStateChange(request("PUT", "/api/v1/bookmarks/id", { origin: `${expectedOrigin}/` }), expectedOrigin),
    ).toBe(false);
    expect(
      isSameOriginStateChange(request("DELETE", "/api/v1/bookmarks/id", { origin: "not a URL" }), expectedOrigin),
    ).toBe(false);
    expect(
      isSameOriginStateChange(
        request("PATCH", "/api/v1/bookmarks/id", { origin: expectedOrigin, "sec-fetch-site": "cross-site" }),
        expectedOrigin,
      ),
    ).toBe(false);
  });

  it("uses the first header value and scopes hardening headers away from proxied API semantics", () => {
    expect(headerValue(request("GET", "/", { origin: ["http://first.test", "http://second.test"] }), "origin")).toBe(
      "http://first.test",
    );

    const apiHeader = vi.fn();
    applySecurityHeaders(
      request("GET", "/api/v1/messages/bundle"),
      { header: apiHeader } as unknown as FastifyReply,
      true,
    );
    expect(apiHeader).toHaveBeenCalledWith("X-Content-Type-Options", "nosniff");
    expect(apiHeader).toHaveBeenCalledWith("Strict-Transport-Security", STRICT_TRANSPORT_SECURITY);
    expect(apiHeader).not.toHaveBeenCalledWith("Content-Security-Policy", expect.anything());

    const browserHeader = vi.fn();
    applySecurityHeaders(request("GET", "/auth/session"), { header: browserHeader } as unknown as FastifyReply, false);
    expect(browserHeader).toHaveBeenCalledWith("Content-Security-Policy", CONTENT_SECURITY_POLICY);
    expect(browserHeader).toHaveBeenCalledWith("X-Frame-Options", "DENY");
    expect(browserHeader).not.toHaveBeenCalledWith("Strict-Transport-Security", expect.anything());
  });
});

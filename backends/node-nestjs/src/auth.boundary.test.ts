import { beforeEach, describe, expect, it, vi } from "vitest";

const {
  createRemoteJWKSetMock,
  decodeProtectedHeaderMock,
  jwtVerifyMock,
  queryMock,
  logEventMock,
  resolveRequestLanguageMock,
  localizeMock,
} = vi.hoisted(() => ({
  createRemoteJWKSetMock: vi.fn(() => vi.fn()),
  decodeProtectedHeaderMock: vi.fn(() => ({ alg: "RS256" })),
  jwtVerifyMock: vi.fn(),
  queryMock: vi.fn(),
  logEventMock: vi.fn(),
  resolveRequestLanguageMock: vi.fn(),
  localizeMock: vi.fn(),
}));

vi.mock("jose", () => ({
  createRemoteJWKSet: createRemoteJWKSetMock,
  decodeProtectedHeader: decodeProtectedHeaderMock,
  jwtVerify: jwtVerifyMock,
  errors: {
    JWTClaimValidationFailed: class JWTClaimValidationFailed extends Error {
      code = "ERR_JWT_CLAIM_VALIDATION_FAILED";
    },
  },
}));
vi.mock("./config.js", () => ({
  config: {
    oidc: {
      issuerUri: "https://idp.example.test/realms/stackverse",
      jwksUri: "https://idp.example.test/jwks",
      audience: "stackverse-api",
    },
  },
}));
vi.mock("./db.js", () => ({ pool: { query: queryMock } }));
vi.mock("./logging.js", () => ({ logEvent: logEventMock }));
vi.mock("./i18n.js", () => ({
  resolveRequestLanguage: resolveRequestLanguageMock,
  localize: localizeMock,
}));

import { authenticateBearerRequest } from "./auth.js";
import { UnauthorizedProblem } from "./problems.js";

const requestWith = (authorization?: string, routeUrl: string | undefined = "/api/v1/me") =>
  ({
    caller: { username: "stale", roles: ["admin"] },
    routeOptions: { url: routeUrl },
    headers: authorization === undefined ? {} : { authorization, "accept-language": "pl" },
    query: {},
  }) as never;

beforeEach(() => {
  decodeProtectedHeaderMock.mockClear();
  jwtVerifyMock.mockReset();
  queryMock.mockReset();
  logEventMock.mockReset();
  resolveRequestLanguageMock.mockReset();
  localizeMock.mockReset();
});

describe("bearer authentication boundary", () => {
  it("verifies issuer/audience, lazily upserts the account, and is idempotent per request", async () => {
    jwtVerifyMock.mockResolvedValueOnce({
      payload: {
        preferred_username: "demo",
        name: "Demo User",
        email: "demo@example.com",
        realm_access: { roles: ["moderator", "offline_access", 42] },
      },
    });
    queryMock.mockResolvedValueOnce({ rows: [{ status: "active" }] });
    const request = requestWith("Bearer signed-token") as {
      caller: { username: string; roles: string[]; name?: string; email?: string } | null;
    };

    await authenticateBearerRequest(request as never);
    await authenticateBearerRequest(request as never);

    expect(decodeProtectedHeaderMock).toHaveBeenCalledOnce();
    expect(decodeProtectedHeaderMock).toHaveBeenCalledWith("signed-token");
    expect(createRemoteJWKSetMock).toHaveBeenCalledWith(new URL("https://idp.example.test/jwks"));
    expect(jwtVerifyMock).toHaveBeenCalledWith("signed-token", expect.any(Function), {
      issuer: "https://idp.example.test/realms/stackverse",
      audience: "stackverse-api",
    });
    expect(queryMock).toHaveBeenCalledOnce();
    expect(queryMock.mock.calls[0]?.[0]).toContain("on conflict (username) do update set last_seen");
    expect(queryMock.mock.calls[0]?.[1]).toEqual(["demo", expect.any(Date)]);
    expect(request.caller).toEqual({
      username: "demo",
      name: "Demo User",
      email: "demo@example.com",
      roles: ["moderator", "offline_access"],
    });
  });

  it("leaves missing tokens anonymous and ignores bearer tokens on unmatched routes", async () => {
    const anonymous = requestWith() as { caller: unknown };
    await authenticateBearerRequest(anonymous as never);
    expect(anonymous.caller).toBeNull();

    const unmatched = requestWith("Bearer invalid") as {
      caller: unknown;
      routeOptions: { url: string | undefined };
    };
    unmatched.routeOptions.url = undefined;
    await authenticateBearerRequest(unmatched as never);
    expect(unmatched.caller).toBeNull();
    expect(decodeProtectedHeaderMock).not.toHaveBeenCalled();
    expect(queryMock).not.toHaveBeenCalled();
  });

  it("rejects blocked accounts with localized detail and a token-free security event", async () => {
    jwtVerifyMock.mockResolvedValueOnce({
      payload: { preferred_username: "blocked-user", realm_access: { roles: [] } },
    });
    queryMock.mockResolvedValueOnce({ rows: [{ status: "blocked" }] });
    resolveRequestLanguageMock.mockResolvedValueOnce("pl");
    localizeMock.mockResolvedValueOnce("Konto jest zablokowane.");

    await expect(authenticateBearerRequest(requestWith("Bearer secret-token"))).rejects.toMatchObject({
      status: 403,
      title: "Forbidden",
      detail: "Konto jest zablokowane.",
    });

    expect(resolveRequestLanguageMock).toHaveBeenCalledWith({}, "pl");
    expect(localizeMock).toHaveBeenCalledWith("error.account.blocked", "pl");
    expect(logEventMock).toHaveBeenCalledWith(
      "warn",
      "blocked_user_rejected",
      "denied",
      "Refused a request from a blocked account",
      { actor: "blocked-user" },
    );
    expect(JSON.stringify(logEventMock.mock.calls)).not.toContain("secret-token");
  });

  it("maps JWT failures and missing usernames to 401 without touching persistence or logging tokens", async () => {
    jwtVerifyMock.mockRejectedValueOnce(Object.assign(new Error("expired"), { code: "ERR_JWT_EXPIRED" }));
    await expect(authenticateBearerRequest(requestWith("Bearer expired-secret"))).rejects.toBeInstanceOf(
      UnauthorizedProblem,
    );
    expect(queryMock).not.toHaveBeenCalled();
    expect(logEventMock).toHaveBeenLastCalledWith(
      "info",
      "jwt_validation_failed",
      "failure",
      "Rejected a bearer token",
      { error_code: "err_jwt_expired" },
    );

    jwtVerifyMock.mockResolvedValueOnce({ payload: { realm_access: { roles: ["admin"] } } });
    await expect(authenticateBearerRequest(requestWith("Bearer username-less-secret"))).rejects.toBeInstanceOf(
      UnauthorizedProblem,
    );
    expect(queryMock).not.toHaveBeenCalled();
    expect(JSON.stringify(logEventMock.mock.calls)).not.toContain("secret");
  });
});

import {
  test as base,
  expect,
  request as playwrightRequest,
  type APIRequestContext,
  type APIResponse,
} from "@playwright/test";
import { readFileSync } from "node:fs";
import { join } from "node:path";

/** Dev realm users (infra/keycloak) — password equals username. `demo` and
 *  `mentor` carry no role; `admin` is a composite that includes `moderator`. */
export const ROLES = ["demo", "mentor", "moderator", "admin"] as const;
export type Role = (typeof ROLES)[number];

const keycloakUrl = process.env["KEYCLOAK_URL"] ?? "http://localhost:8180";
const backendUrl = process.env["BACKEND_URL"] ?? "http://localhost:8080";

/** Unique-per-run suffix so test data never collides with previous runs. */
export const uid = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 6);

/** The seed files are part of the contract (SPEC rule 12) — localized
 *  validation asserts against them, not against copied strings. */
export const seedMessages = (language: "en" | "pl"): Record<string, string> =>
  JSON.parse(readFileSync(join(__dirname, "..", "..", "spec", "messages", `${language}.json`), "utf8"));

interface CachedToken {
  value: string;
  expiresAt: number;
}
const tokens = new Map<Role, CachedToken>();

/** Password grant against the dev realm's `stackverse-conformance` client. */
export async function accessToken(role: Role): Promise<string> {
  const cached = tokens.get(role);
  if (cached && cached.expiresAt - Date.now() > 30_000) return cached.value;
  const keycloak = await playwrightRequest.newContext();
  try {
    const response = await keycloak.post(
      `${keycloakUrl}/realms/stackverse/protocol/openid-connect/token`,
      {
        form: {
          grant_type: "password",
          client_id: "stackverse-conformance",
          username: role,
          password: role,
        },
      },
    );
    expect(response.ok(), `token for ${role}: ${await response.text()}`).toBe(true);
    const body = (await response.json()) as { access_token: string; expires_in: number };
    tokens.set(role, { value: body.access_token, expiresAt: Date.now() + body.expires_in * 1000 });
    return body.access_token;
  } finally {
    await keycloak.dispose();
  }
}

async function apiContext(role?: Role): Promise<APIRequestContext> {
  return playwrightRequest.newContext({
    baseURL: backendUrl,
    extraHTTPHeaders: role === undefined ? {} : { Authorization: `Bearer ${await accessToken(role)}` },
  });
}

interface ApiFixtures {
  /** Regular user without any role. */
  demo: APIRequestContext;
  /** Second role-less user — the "other user" in ownership tests, and the
   *  one that gets blocked/unblocked (so `demo` data stays undisturbed). */
  mentor: APIRequestContext;
  moderator: APIRequestContext;
  admin: APIRequestContext;
  /** No Authorization header — the anonymous public surface. */
  anon: APIRequestContext;
}

export const test = base.extend<ApiFixtures>({
  demo: async ({}, use) => {
    const api = await apiContext("demo");
    await use(api);
    await api.dispose();
  },
  mentor: async ({}, use) => {
    const api = await apiContext("mentor");
    await use(api);
    await api.dispose();
  },
  moderator: async ({}, use) => {
    const api = await apiContext("moderator");
    await use(api);
    await api.dispose();
  },
  admin: async ({}, use) => {
    const api = await apiContext("admin");
    await use(api);
    await api.dispose();
  },
  anon: async ({}, use) => {
    const api = await apiContext();
    await use(api);
    await api.dispose();
  },
});

export { expect };

/** Asserts an RFC 9457 problem response and returns the parsed document. */
export async function expectProblem(
  response: APIResponse,
  status: number,
): Promise<{
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  errors?: { field: string; messageKey: string; message: string }[];
}> {
  const text = await response.text();
  expect(response.status(), text).toBe(status);
  expect(response.headers()["content-type"], text).toContain("application/problem+json");
  return JSON.parse(text);
}

export interface Bookmark {
  id: string;
  url: string;
  title: string;
  notes?: string;
  tags: string[];
  visibility: "private" | "public";
  status: "active" | "hidden";
  owner: string;
  createdAt: string;
  updatedAt: string;
}

export interface BookmarkSeed {
  url: string;
  title: string;
  notes?: string;
  tags?: string[];
  visibility?: "private" | "public";
}

export async function createBookmark(api: APIRequestContext, seed: BookmarkSeed): Promise<Bookmark> {
  const response = await api.post("/api/v1/bookmarks", { data: seed });
  expect(response.status(), await response.text()).toBe(201);
  return (await response.json()) as Bookmark;
}

export interface Report {
  id: string;
  bookmarkId: string;
  reporter: string;
  reason: string;
  comment?: string;
  status: "open" | "dismissed" | "actioned";
  createdAt: string;
  resolvedBy?: string;
  resolvedAt?: string;
  resolutionNote?: string;
}

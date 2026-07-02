import { expect, type APIResponse, type Page } from "@playwright/test";

/** Dev realm users (infra/keycloak) — password equals username. */
export const ROLES = ["demo", "moderator", "admin"] as const;
export type Role = (typeof ROLES)[number];

/** Per-role login state written by auth.setup.ts, consumed via test.use. */
export const authFile = (role: Role) => `.auth/${role}.json`;

/** Unique-per-run suffix so test data never collides with previous runs. */
export const uid = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 6);

/** Real OIDC login: gateway challenge -> Keycloak form -> callback -> SPA. */
export async function loginViaKeycloak(page: Page, role: Role) {
  await page.goto("/auth/login");
  await page.locator("#username").fill(role);
  await page.locator("#password").fill(role);
  await page.locator("#kc-login").click();
  await expect(page.locator(".sv-username")).toHaveText(role);
}

/**
 * State-changing API call with the page's session, echoing the XSRF-TOKEN
 * cookie exactly as the SPA does — for arranging test data, not for testing.
 */
export async function apiMutate(
  page: Page,
  method: "POST" | "PUT" | "PATCH" | "DELETE",
  path: string,
  body?: unknown,
): Promise<APIResponse> {
  const cookies = await page.context().cookies();
  const xsrf = cookies.find((cookie) => cookie.name === "XSRF-TOKEN")?.value ?? "";
  return page.request.fetch(path, {
    method,
    headers: { "X-XSRF-TOKEN": xsrf },
    ...(body === undefined ? {} : { data: body }),
  });
}

export interface BookmarkSeed {
  url: string;
  title: string;
  notes?: string;
  tags?: string[];
  visibility: "private" | "public";
}

/** Creates a bookmark through the API and returns its id. */
export async function createBookmark(page: Page, seed: BookmarkSeed): Promise<string> {
  const response = await apiMutate(page, "POST", "/api/v1/bookmarks", seed);
  expect(response.status(), await response.text()).toBe(201);
  const created = (await response.json()) as { id: string };
  return created.id;
}

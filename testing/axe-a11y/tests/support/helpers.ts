import { expect, type APIResponse, type Page } from "@playwright/test";

export const ROLES = ["demo", "moderator", "admin"] as const;
export type Role = (typeof ROLES)[number];

export const authFile = (role: Role) => `.auth/${role}.json`;

export const uid = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 6);

export async function loginViaKeycloak(page: Page, role: Role): Promise<void> {
  await page.goto("/auth/login");
  await page.locator("#username").fill(role);
  await page.locator("#password").fill(role);
  await page.locator("#kc-login").click();
  await expect(page.locator(".sv-username")).toHaveText(role);
}

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

export async function createBookmark(page: Page, seed: BookmarkSeed): Promise<string> {
  const response = await apiMutate(page, "POST", "/api/v1/bookmarks", seed);
  expect(response.status(), await response.text()).toBe(201);
  const created = (await response.json()) as { id: string };
  return created.id;
}

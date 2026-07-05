import { beforeEach, describe, expect, it, vi } from "vitest";
import { isAdmin, isModerator } from "../auth";
import type { User } from "../types";

function userWithRoles(roles: string[]): User {
  return { username: "demo", roles };
}

describe("role helpers", () => {
  it("treats admins as moderators even when the token lacks a moderator role", () => {
    const admin = userWithRoles(["admin"]);

    expect(isAdmin(admin)).toBe(true);
    expect(isModerator(admin)).toBe(true);
  });

  it("recognizes a direct moderator role", () => {
    expect(isModerator(userWithRoles(["moderator"]))).toBe(true);
    expect(isModerator(userWithRoles([]))).toBe(false);
  });
});

describe("reported bookmark store", () => {
  beforeEach(() => {
    vi.resetModules();
  });

  it("persists reported ids to session storage", async () => {
    const { isReported, markReported, unmarkReported } = await import("../reportedStore");

    markReported("bookmark-1");
    expect(isReported("bookmark-1")).toBe(true);
    expect(JSON.parse(sessionStorage.getItem("stackverse.reported") ?? "[]")).toEqual([
      "bookmark-1",
    ]);

    unmarkReported("bookmark-1");
    expect(isReported("bookmark-1")).toBe(false);
    expect(JSON.parse(sessionStorage.getItem("stackverse.reported") ?? "[]")).toEqual([]);
  });

  it("hydrates reported ids from session storage", async () => {
    sessionStorage.setItem("stackverse.reported", JSON.stringify(["bookmark-2"]));

    const { isReported } = await import("../reportedStore");

    expect(isReported("bookmark-2")).toBe(true);
  });
});

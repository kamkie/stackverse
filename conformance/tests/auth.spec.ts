// AuthN/AuthZ contract: bearer validation, /me identity (SPEC rule 6),
// hierarchical roles resolved in Keycloak, 403 problem documents (SPEC "Roles").
import { expect, expectProblem, test } from "./fixtures";

interface Me {
  username: string;
  roles: string[];
}

test("a request without a token is rejected with a 401 problem", async ({ anon }) => {
  const response = await anon.get("/api/v1/me");
  await expectProblem(response, 401);
});

test("a garbage bearer token is rejected with a 401 problem", async ({ anon }) => {
  const response = await anon.get("/api/v1/me", {
    headers: { Authorization: "Bearer not-a-jwt" },
  });
  await expectProblem(response, 401);
});

test("me echoes the JWT identity; a regular user has no roles", async ({ demo }) => {
  const response = await demo.get("/api/v1/me");
  expect(response.status(), await response.text()).toBe(200);
  const me = (await response.json()) as Me;
  expect(me.username).toBe("demo");
  expect(me.roles).toEqual([]);
});

test("a moderator token carries moderator but not admin", async ({ moderator }) => {
  const me = (await (await moderator.get("/api/v1/me")).json()) as Me;
  expect(me.username).toBe("moderator");
  expect(me.roles).toContain("moderator");
  expect(me.roles).not.toContain("admin");
});

test("the admin composite role expands to both role strings", async ({ admin }) => {
  const me = (await (await admin.get("/api/v1/me")).json()) as Me;
  expect(me.username).toBe("admin");
  expect(me.roles).toEqual(expect.arrayContaining(["admin", "moderator"]));
});

test("moderator endpoints reject a role-less user with a 403 problem", async ({ demo }) => {
  const problem = await expectProblem(await demo.get("/api/v1/admin/reports"), 403);
  expect(problem.status).toBe(403);
  await expectProblem(await demo.get("/api/v1/admin/stats"), 403);
});

test("admin endpoints reject a moderator (no hierarchy re-implementation upward)", async ({ moderator }) => {
  await expectProblem(await moderator.get("/api/v1/admin/users"), 403);
  await expectProblem(await moderator.get("/api/v1/admin/audit-log"), 403);
});

test("moderator-level endpoints accept both moderator and admin", async ({ moderator, admin }) => {
  expect((await moderator.get("/api/v1/admin/reports")).status()).toBe(200);
  expect((await admin.get("/api/v1/admin/reports")).status()).toBe(200);
});

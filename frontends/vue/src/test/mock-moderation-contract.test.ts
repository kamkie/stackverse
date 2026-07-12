import { setupServer } from "msw/node";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import type { components } from "../api/schema";
import { db, resetDb } from "../mocks/db";
import { handlers } from "../mocks/handlers";
import { MOCK_USERS, setCurrentUser } from "../mocks/state";

type Problem = components["schemas"]["Problem"];

const server = setupServer(...handlers);

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));

afterEach(() => {
  server.resetHandlers();
  resetDb();
  setCurrentUser(null);
});

afterAll(() => server.close());

describe("moderation mock contract", () => {
  it("rejects reopening when the reporter already has another open report", async () => {
    const target = db.reports.find((report) => report.status === "open");
    if (!target) throw new Error("report seed data is incomplete");

    target.status = "dismissed";
    db.reports.push({
      ...target,
      id: "00000000-0000-4000-8000-999999999999",
      status: "open",
    });
    const auditCount = db.audit.length;
    setCurrentUser(MOCK_USERS.moderator);

    const response = await fetch(new URL(`/api/v1/admin/reports/${target.id}`, location.origin), {
      method: "PUT",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ resolution: "open" }),
    });

    expect(response.status).toBe(409);
    expect((await response.json()) as Problem).toMatchObject({
      status: 409,
      title: "Conflict",
    });
    expect(target.status).toBe("dismissed");
    expect(db.audit).toHaveLength(auditCount);
  });
});

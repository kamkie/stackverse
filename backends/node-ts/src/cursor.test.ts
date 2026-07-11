import { describe, expect, it } from "vitest";
import { decodeCursor, encodeCursor } from "./cursor.js";
import { BadRequestProblem } from "./problems.js";

describe("bookmark cursor", () => {
  it("round-trips (createdAt, id)", () => {
    const cursor = { createdAt: new Date("2026-07-03T12:34:56.789Z"), id: "0f8fad5b-d9cb-469f-a165-70867728950e" };
    const decoded = decodeCursor(encodeCursor(cursor));
    expect(decoded.createdAt.toISOString()).toBe("2026-07-03T12:34:56.789Z");
    expect(decoded.id).toBe(cursor.id);
  });

  it("is opaque base64url without padding", () => {
    const encoded = encodeCursor({ createdAt: new Date(), id: "0f8fad5b-d9cb-469f-a165-70867728950e" });
    expect(encoded).toMatch(/^[A-Za-z0-9_-]+$/);
  });

  it.each([
    "definitely-not-a-cursor",
    "",
    "aGVsbG8",
    Buffer.from("2026-01-01T00:00:00Z|not-a-uuid").toString("base64url"),
  ])("rejects malformed cursor %j with a 400 problem", (value) => {
    expect(() => decodeCursor(value)).toThrow(BadRequestProblem);
  });
});

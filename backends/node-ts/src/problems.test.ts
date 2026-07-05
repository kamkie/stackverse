import Fastify from "fastify";
import { describe, expect, it } from "vitest";
import {
  BadRequestProblem,
  NotFoundProblem,
  ValidationProblem,
  Validator,
  escapeLike,
  firstParam,
  multiParam,
  omitNulls,
  parseUuid,
  requireMaxLength,
  requireValidPaging,
  sendProblem,
  singleParam,
} from "./problems.js";

describe("problem documents", () => {
  it("renders RFC 9457 fields and optional validation errors", async () => {
    const app = Fastify({ logger: false });
    app.get("/problem", (_request, reply) =>
      sendProblem(reply, 400, "Bad Request", "Request validation failed.", [
        { field: "title", messageKey: "validation.title.required", message: "Title is required." },
      ]),
    );

    try {
      const response = await app.inject({ method: "GET", url: "/problem" });

      expect(response.statusCode).toBe(400);
      expect(response.headers["content-type"]).toContain("application/problem+json");
      expect(response.json()).toEqual({
        type: "about:blank",
        title: "Bad Request",
        status: 400,
        detail: "Request validation failed.",
        errors: [{ field: "title", messageKey: "validation.title.required", message: "Title is required." }],
      });
    } finally {
      await app.close();
    }
  });
});

describe("validation helper", () => {
  it("collects violations and throws them together", () => {
    const validator = new Validator();
    validator.reject("url", "validation.url.required");
    validator.check(false, "title", "validation.title.required");

    expect(() => validator.throwIfInvalid()).toThrow(ValidationProblem);
    try {
      validator.throwIfInvalid();
    } catch (error) {
      expect((error as ValidationProblem).violations).toEqual([
        { field: "url", messageKey: "validation.url.required" },
        { field: "title", messageKey: "validation.title.required" },
      ]);
    }
  });
});

describe("query and wire helpers", () => {
  it("accepts defaults for paging and rejects out-of-contract values", () => {
    expect(requireValidPaging({})).toEqual({ page: 0, size: 20 });
    expect(requireValidPaging({ page: "2", size: "100" })).toEqual({ page: 2, size: 100 });

    expect(() => requireValidPaging({ page: "-1" })).toThrow(BadRequestProblem);
    expect(() => requireValidPaging({ size: "0" })).toThrow(BadRequestProblem);
    expect(() => requireValidPaging({ size: "101" })).toThrow(BadRequestProblem);
    expect(() => requireValidPaging({ page: "1.5" })).toThrow(BadRequestProblem);
    expect(() => requireValidPaging({ page: ["1", "2"] })).toThrow(BadRequestProblem);
  });

  it("handles single, first, and repeated query parameter shapes intentionally", () => {
    expect(singleParam(undefined, "q")).toBeUndefined();
    expect(singleParam("needle", "q")).toBe("needle");
    expect(() => singleParam(["a", "b"], "q")).toThrow(BadRequestProblem);

    expect(firstParam(["pl", "en"])).toBe("pl");
    expect(firstParam(123)).toBeUndefined();

    expect(multiParam(undefined)).toEqual([]);
    expect(multiParam("tag")).toEqual(["tag"]);
    expect(multiParam(["one", 2])).toEqual(["one", "2"]);
  });

  it("normalizes UUIDs, masks malformed IDs as not found, and omits null optional fields", () => {
    expect(parseUuid("0F8FAD5B-D9CB-469F-A165-70867728950E")).toBe("0f8fad5b-d9cb-469f-a165-70867728950e");
    expect(() => parseUuid("not-a-uuid")).toThrow(NotFoundProblem);

    expect(omitNulls({ required: "value", empty: null, missing: undefined })).toEqual({ required: "value" });
  });

  it("escapes LIKE wildcards and enforces maximum string lengths", () => {
    expect(escapeLike(String.raw`100%_done\soon`)).toBe(String.raw`100\%\_done\\soon`);

    expect(() => requireMaxLength("x".repeat(3), 3, "q")).not.toThrow();
    expect(() => requireMaxLength("x".repeat(4), 3, "q")).toThrow(BadRequestProblem);
  });
});

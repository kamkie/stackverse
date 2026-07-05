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

const replyStub = () => {
  const reply = {
    statusCode: undefined as number | undefined,
    contentType: undefined as string | undefined,
    body: undefined as unknown,
    code(status: number) {
      this.statusCode = status;
      return this;
    },
    type(value: string) {
      this.contentType = value;
      return this;
    },
    send(body: unknown) {
      this.body = body;
      return this;
    },
  };
  return reply;
};

describe("problem documents", () => {
  it("renders RFC 9457 problem responses with optional details and errors", () => {
    const reply = replyStub();

    sendProblem(reply as never, 400, "Bad Request", "Invalid input", [
      { field: "title", messageKey: "validation.title.required", message: "Required" },
    ]);

    expect(reply.statusCode).toBe(400);
    expect(reply.contentType).toBe("application/problem+json");
    expect(reply.body).toEqual({
      type: "about:blank",
      title: "Bad Request",
      status: 400,
      detail: "Invalid input",
      errors: [{ field: "title", messageKey: "validation.title.required", message: "Required" }],
    });
  });

  it("omits absent optional problem fields", () => {
    const reply = replyStub();

    sendProblem(reply as never, 404, "Not Found");

    expect(reply.body).toEqual({ type: "about:blank", title: "Not Found", status: 404 });
  });
});

describe("validation helpers", () => {
  it("collects multiple validator failures before throwing", () => {
    const validator = new Validator();

    validator.check(false, "url", "validation.url.required");
    validator.reject("title", "validation.title.required");

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

  it("does not throw when every validator check passes", () => {
    const validator = new Validator();

    validator.check(true, "url", "validation.url.required");

    expect(() => validator.throwIfInvalid()).not.toThrow();
  });
});

describe("query parameter helpers", () => {
  it("parses single, first, and repeated parameters according to each route contract", () => {
    expect(singleParam(undefined, "q")).toBeUndefined();
    expect(singleParam("search", "q")).toBe("search");
    expect(() => singleParam(["a", "b"], "q")).toThrow(BadRequestProblem);

    expect(firstParam(["pl", "en"])).toBe("pl");
    expect(firstParam(42)).toBeUndefined();

    expect(multiParam(undefined)).toEqual([]);
    expect(multiParam("tag")).toEqual(["tag"]);
    expect(multiParam(["a", 2])).toEqual(["a", "2"]);
  });

  it("enforces default and explicit paging bounds", () => {
    expect(requireValidPaging({})).toEqual({ page: 0, size: 20 });
    expect(requireValidPaging({ page: "2", size: "50" })).toEqual({ page: 2, size: 50 });

    expect(() => requireValidPaging({ page: "1.5" })).toThrow(BadRequestProblem);
    expect(() => requireValidPaging({ page: "-1" })).toThrow(BadRequestProblem);
    expect(() => requireValidPaging({ size: "0" })).toThrow(BadRequestProblem);
    expect(() => requireValidPaging({ size: "101" })).toThrow(BadRequestProblem);
    expect(() => requireValidPaging({ size: ["20", "30"] })).toThrow(BadRequestProblem);
  });

  it("enforces max-length query bounds only when a value is present", () => {
    expect(() => requireMaxLength(undefined, 3, "q")).not.toThrow();
    expect(() => requireMaxLength("abc", 3, "q")).not.toThrow();
    expect(() => requireMaxLength("abcd", 3, "q")).toThrow(BadRequestProblem);
  });
});

describe("serialization helpers", () => {
  it("escapes LIKE wildcards and backslashes so query text stays literal", () => {
    expect(escapeLike(String.raw`50%\path_`)).toBe(String.raw`50\%\\path\_`);
  });

  it("normalizes valid UUIDs and masks malformed ids as not found", () => {
    expect(parseUuid("0F8FAD5B-D9CB-469F-A165-70867728950E")).toBe(
      "0f8fad5b-d9cb-469f-a165-70867728950e",
    );
    expect(() => parseUuid("not-a-uuid")).toThrow(NotFoundProblem);
  });

  it("omits null and undefined optional fields while preserving falsy values", () => {
    expect(omitNulls({ title: "t", notes: null, count: 0, visible: false, missing: undefined })).toEqual({
      title: "t",
      count: 0,
      visible: false,
    });
  });
});

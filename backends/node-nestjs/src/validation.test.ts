import { describe, expect, it } from "vitest";
import { validateBookmarkInput, validateQueryTags } from "./bookmarks/bookmarks.service.js";
import { ValidationProblem } from "./problems.js";

const violations = (body: unknown): { field: string; messageKey: string }[] => {
  try {
    validateBookmarkInput(body);
    return [];
  } catch (error) {
    if (error instanceof ValidationProblem) return error.violations;
    throw error;
  }
};

describe("bookmark validation (SPEC rules 5 + 11)", () => {
  it("accepts a minimal valid input and applies defaults", () => {
    const input = validateBookmarkInput({ url: "https://example.com", title: " t " });
    expect(input.title).toBe("t");
    expect(input.visibility).toBe("private");
    expect(input.tags).toEqual([]);
  });

  it("normalizes tags before validating: trimmed, lowercased, deduplicated", () => {
    const input = validateBookmarkInput({
      url: "https://example.com",
      title: "t",
      tags: [" Kotlin ", "kotlin", "web"],
    });
    expect(input.tags).toEqual(["kotlin", "web"]);
  });

  it("collects every field error into one problem", () => {
    const fields = violations({}).map((violation) => violation.field);
    expect(fields).toContain("url");
    expect(fields).toContain("title");
  });

  it.each([
    ["/not/absolute", "validation.url.invalid"],
    ["ftp://example.com", "validation.url.invalid"],
    ["", "validation.url.required"],
    ["https://", "validation.url.invalid"],
  ])("rejects url %j with %s", (url, messageKey) => {
    expect(violations({ url, title: "t" })).toContainEqual({ field: "url", messageKey });
  });

  it("bounds title, notes and tags", () => {
    expect(violations({ url: "https://example.com", title: "x".repeat(201) })).toContainEqual({
      field: "title",
      messageKey: "validation.title.too-long",
    });
    expect(violations({ url: "https://example.com", title: "t", notes: "x".repeat(4001) })).toContainEqual({
      field: "notes",
      messageKey: "validation.notes.too-long",
    });
    expect(
      violations({ url: "https://example.com", title: "t", tags: Array.from({ length: 11 }, (_, i) => `t-${i}`) }),
    ).toContainEqual({ field: "tags", messageKey: "validation.tags.too-many" });
    expect(violations({ url: "https://example.com", title: "t", tags: ["no spaces!"] })).toContainEqual({
      field: "tags",
      messageKey: "validation.tag.invalid",
    });
  });

  it("normalizes and validates query tags", () => {
    expect(validateQueryTags([" Node ", "web"])).toEqual(["node", "web"]);

    expect(() => validateQueryTags(["valid", "no spaces!"])).toThrow(ValidationProblem);
    try {
      validateQueryTags(["valid", "no spaces!"]);
    } catch (error) {
      expect(error).toBeInstanceOf(ValidationProblem);
      expect((error as ValidationProblem).violations).toContainEqual({
        field: "tag",
        messageKey: "validation.tag.invalid",
      });
    }
  });
});

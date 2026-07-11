import { describe, expect, it } from "vitest";
import { ApiError, fieldErrorFor, fieldErrorsOf } from "../api/problem";
import { tagsFromInput, tagsToInput, toFieldErrorMap } from "../forms";
import type { FieldError, Problem } from "../api/problem";

const fieldErrors: FieldError[] = [
  {
    field: "title",
    messageKey: "validation.required",
    message: "Title is required",
  },
  { field: "tags[0]", messageKey: "validation.tag", message: "Invalid tag" },
  {
    field: "tags.1",
    messageKey: "validation.tag",
    message: "Second invalid tag",
  },
  {
    field: "notes",
    messageKey: "validation.length",
    message: "Notes are too long",
  },
];

describe("API problem helpers", () => {
  it("uses problem detail/title fallbacks for ApiError messages", () => {
    expect(new ApiError(400, { title: "Bad request", detail: "Invalid input" }).message).toBe(
      "Invalid input",
    );
    expect(new ApiError(404, { title: "Not found" }).message).toBe("Not found");
    expect(new ApiError(503).message).toBe("HTTP 503");
  });

  it("exposes field errors only for ApiError instances", () => {
    const problem: Problem = {
      title: "Validation failed",
      status: 400,
      errors: fieldErrors,
    };
    const error = new ApiError(400, problem);

    expect(fieldErrorsOf(error)).toEqual(fieldErrors);
    expect(fieldErrorsOf(new Error("boom"))).toEqual([]);
  });

  it("matches exact, indexed, and dotted nested field paths", () => {
    const error = new ApiError(400, {
      title: "Validation failed",
      status: 400,
      errors: fieldErrors,
    });

    expect(error.fieldError("title")?.message).toBe("Title is required");
    expect(fieldErrorFor(error, "tags")?.message).toBe("Invalid tag");
    expect(fieldErrorFor(error, "url")).toBeUndefined();
  });
});

describe("form helpers", () => {
  it("groups nested field errors by top-level field and keeps the first message", () => {
    expect(toFieldErrorMap(fieldErrors)).toEqual({
      title: "Title is required",
      tags: "Invalid tag",
      notes: "Notes are too long",
    });
  });

  it("normalizes tag input without changing case or server-owned validation rules", () => {
    expect(tagsFromInput(" vue, testing  contract\napi\t")).toEqual([
      "vue",
      "testing",
      "contract",
      "api",
    ]);
    expect(tagsToInput(["vue", "contract"])).toBe("vue contract");
    expect(tagsToInput(undefined)).toBe("");
  });
});

import { ApiError, buildUrl, fieldErrorFor } from "./api";
import type { Problem } from "./types";

describe("buildUrl", () => {
  it("serializes repeatable query parameters and skips blanks", () => {
    const url = new URL(
      buildUrl("/api/v2/bookmarks", {
        tag: ["dev", "http"],
        q: "etag",
        cursor: "",
        page: 0,
      }),
    );

    expect(url.pathname).toBe("/api/v2/bookmarks");
    expect(url.searchParams.getAll("tag")).toEqual(["dev", "http"]);
    expect(url.searchParams.get("q")).toBe("etag");
    expect(url.searchParams.get("page")).toBe("0");
    expect(url.searchParams.has("cursor")).toBe(false);
  });
});

describe("fieldErrorFor", () => {
  it("matches exact and indexed field paths", () => {
    const problem: Problem = {
      errors: [
        { field: "url", messageKey: "validation.url.required", message: "URL is required." },
        { field: "tags[0]", messageKey: "validation.tag.invalid", message: "Bad tag." },
      ],
    };
    const error = new ApiError(400, problem);

    expect(fieldErrorFor(error, "url")?.messageKey).toBe("validation.url.required");
    expect(fieldErrorFor(error, "tags")?.messageKey).toBe("validation.tag.invalid");
    expect(fieldErrorFor(error, "title")).toBeUndefined();
  });
});


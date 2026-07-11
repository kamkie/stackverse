import { afterEach, describe, expect, it, vi } from "vitest";
import { logEvent, logger, sanitizeLogValue } from "./logging.js";

afterEach(() => {
  vi.restoreAllMocks();
});

describe("structured logging boundaries", () => {
  it("sanitizes control characters, encodes newlines, and caps client-controlled values", () => {
    expect(sanitizeLogValue(undefined)).toBeUndefined();
    expect(sanitizeLogValue("\u0000alpha\r\nbeta\rgamma\nomega\u0007")).toBe("alpha\\nbeta\\ngamma\\nomega");
    expect(sanitizeLogValue("abcdef", 3)).toBe("abc...");
  });

  it("keeps stable event metadata separate from the human-readable message", () => {
    const info = vi.spyOn(logger, "info").mockImplementation(() => undefined);

    logEvent("info", "csrf_validation_failed", "denied", "Rejected a request", {
      method: "POST",
      path: "/api/v1/bookmarks",
    });

    expect(info).toHaveBeenCalledWith(
      {
        event: "csrf_validation_failed",
        outcome: "denied",
        method: "POST",
        path: "/api/v1/bookmarks",
      },
      "Rejected a request",
    );
  });
});

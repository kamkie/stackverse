import { describe, expect, it } from "vitest";
import { isReported, markReported, removeReported } from "./reportedStore";

describe("reported bookmark state", () => {
  it("tracks duplicate-report state within the current browser runtime", () => {
    const id = "bookmark-report-state";
    removeReported(id);

    expect(isReported(id)).toBe(false);
    markReported(id);
    markReported(id);
    expect(isReported(id)).toBe(true);
    removeReported(id);
    expect(isReported(id)).toBe(false);
  });
});

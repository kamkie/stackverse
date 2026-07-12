import { beforeEach, describe, expect, it } from "vitest";
import { isReported, markReported, removeReported } from "./reportedStore";

beforeEach(() => sessionStorage.clear());

describe("reported bookmark session state", () => {
  it("persists and removes reported ids in session storage", () => {
    markReported("bookmark-1");
    expect(isReported("bookmark-1")).toBe(true);
    expect(sessionStorage.getItem("stackverse.reported")).toContain("bookmark-1");

    removeReported("bookmark-1");
    expect(isReported("bookmark-1")).toBe(false);
  });
});

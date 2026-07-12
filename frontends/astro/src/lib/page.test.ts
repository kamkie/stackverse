import { describe, expect, it } from "vitest";
import { previousPageForEmpty } from "./page";

describe("previousPageForEmpty", () => {
  it("steps back when the current page became empty", () => {
    expect(
      previousPageForEmpty(
        { items: [], page: 2, size: 20, totalItems: 40, totalPages: 2 },
        2,
      ),
    ).toBe(1);
  });

  it("does not recurse when the backend still reports the same page", () => {
    expect(
      previousPageForEmpty(
        { items: [], page: 2, size: 20, totalItems: 60, totalPages: 3 },
        2,
      ),
    ).toBeNull();
  });

  it("leaves populated pages unchanged", () => {
    expect(
      previousPageForEmpty(
        { items: ["item"], page: 1, size: 20, totalItems: 21, totalPages: 2 },
        1,
      ),
    ).toBeNull();
  });
});

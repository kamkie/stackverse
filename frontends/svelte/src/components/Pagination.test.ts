import { fireEvent, render, screen } from "@testing-library/svelte";
import { describe, expect, it, vi } from "vitest";
import Pagination from "./Pagination.svelte";

describe("Pagination", () => {
  it("disables the previous action and emits the next page", async () => {
    const onPage = vi.fn();

    render(Pagination, { page: 0, totalPages: 3, onPage });

    expect(
      screen.getByRole<HTMLButtonElement>("button", { name: "previous" })
        .disabled,
    ).toBe(true);
    await fireEvent.click(screen.getByRole("button", { name: "next" }));
    expect(onPage).toHaveBeenCalledExactlyOnceWith(1);
  });

  it("emits the previous page and disables next on the final page", async () => {
    const onPage = vi.fn();
    render(Pagination, { page: 2, totalPages: 3, onPage });
    expect(
      screen.getByRole<HTMLButtonElement>("button", { name: "next" }).disabled,
    ).toBe(true);
    await fireEvent.click(screen.getByRole("button", { name: "previous" }));
    expect(onPage).toHaveBeenCalledExactlyOnceWith(1);
  });

  it("omits controls when there is only one page", () => {
    const { container } = render(Pagination, {
      page: 0,
      totalPages: 1,
      onPage: vi.fn(),
    });
    expect(container.querySelector(".sv-pagination")).toBeNull();
  });
});

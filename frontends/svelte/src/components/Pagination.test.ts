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
});

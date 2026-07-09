import { cleanup, fireEvent, render, screen } from "@testing-library/svelte";
import { afterEach, describe, expect, it, vi } from "vitest";
import { i18n } from "../lib/i18n";
import Pagination from "./Pagination.svelte";

afterEach(cleanup);

describe("Pagination", () => {
  it("disables the previous action and emits the next page", async () => {
    i18n.set({
      lang: "en",
      resolvedLanguage: "en",
      messages: {
        "ui.action.previous": "Previous",
        "ui.action.next": "Next",
      },
      ready: true,
    });
    const onPage = vi.fn();

    render(Pagination, { page: 0, totalPages: 3, onPage });

    expect(
      (screen.getByRole("button", { name: "Previous" }) as HTMLButtonElement)
        .disabled,
    ).toBe(true);
    await fireEvent.click(screen.getByRole("button", { name: "Next" }));
    expect(onPage).toHaveBeenCalledOnce();
    expect(onPage).toHaveBeenCalledWith(1);
  });
});

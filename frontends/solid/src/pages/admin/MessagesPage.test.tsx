import { fireEvent, render, screen } from "@solidjs/testing-library";
import { expect, it, vi } from "vitest";
import { jsonResponse, NOW, page, readyI18n } from "../../test/fixtures";
import MessagesPage from "./MessagesPage";

it("uses the current search and language for message pagination and clears both filters", async () => {
  readyI18n();
  const fetchMock = vi.fn(async (_url: URL) =>
    jsonResponse(page([{
      id: "message-1",
      key: "ui.test",
      language: "pl",
      text: "Test",
      createdAt: NOW,
      updatedAt: NOW,
    }], { totalPages: 2 })),
  );
  vi.stubGlobal("fetch", fetchMock);
  render(() => <MessagesPage toast={vi.fn()} />);

  await screen.findByText("1 / 2");
  await fireEvent.input(screen.getByRole("textbox"), { target: { value: "test" } });
  await fireEvent.change(screen.getByRole("combobox"), { target: { value: "pl" } });
  await fireEvent.click(screen.getByRole("button", { name: "next" }));
  expect(await screen.findByText("2 / 2")).toBeTruthy();
  await fireEvent.click(screen.getByRole("button", { name: "clear-filters" }));

  expect(fetchMock.mock.calls.map(([url]) => Object.fromEntries(url.searchParams))).toEqual([
    { page: "0" },
    { q: "test", page: "0" },
    { q: "test", language: "pl", page: "0" },
    { q: "test", language: "pl", page: "1" },
    { page: "0" },
  ]);
});

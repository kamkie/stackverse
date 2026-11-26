import { fireEvent, render, screen } from "@solidjs/testing-library";
import { expect, it, vi } from "vitest";
import { endOfDayIso } from "../../lib/format";
import { jsonResponse, NOW, page, readyI18n } from "../../test/fixtures";
import AuditLogPage from "./AuditLogPage";

it("uses the new audit date range and clears it from the next request", async () => {
  readyI18n();
  const fetchMock = vi.fn(async (_url: URL) =>
    jsonResponse(page([{
      id: "audit-1",
      actor: "admin",
      action: "user.blocked",
      targetType: "user",
      targetId: "demo",
      createdAt: NOW,
    }], { totalPages: 2 })),
  );
  vi.stubGlobal("fetch", fetchMock);
  render(() => <AuditLogPage />);

  await fireEvent.click(await screen.findByRole("button", { name: "next" }));
  expect(await screen.findByText("2 / 2")).toBeTruthy();
  await fireEvent.change(screen.getByLabelText("from"), { target: { value: "2026-07-10" } });
  await fireEvent.change(screen.getByLabelText("to"), { target: { value: "2026-07-12" } });
  await fireEvent.click(screen.getByRole("button", { name: "clear-filters" }));

  const from = new Date(2026, 6, 10).toISOString();
  const to = endOfDayIso("2026-07-12");
  expect(fetchMock.mock.calls.map(([url]) => Object.fromEntries(url.searchParams))).toEqual([
    { page: "0" },
    { page: "1" },
    { page: "0", from },
    { page: "0", from, to },
    { page: "0" },
  ]);
});

// Audit log toolbar: the date-range inputs carry visible From/To labels,
// the action filter offers known-action suggestions but stays free-text,
// and "Clear filters" resets every filter and restores the unfiltered list.
import { fireEvent, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { db } from "../mocks/db";
import { MOCK_USERS, setCurrentUser } from "../mocks/state";
import { renderApp } from "./utils";

const ACTION_PLACEHOLDER = "Exact action, e.g. report.resolved";

/** The YYYY-MM-DD value a date input shows for an instant, in local time. */
function localDateValue(date: Date): string {
  const pad = (part: number) => String(part).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

describe("audit log filters", () => {
  it("renders labeled date fields and known-action suggestions", async () => {
    setCurrentUser(MOCK_USERS.admin);
    renderApp("/admin/audit");

    // The single seeded audit entry (admin blocked mallory) is listed.
    expect(await screen.findByText("user.blocked")).toBeInTheDocument();

    // Visible text labels, associated with the date inputs by wrapping.
    expect(screen.getByText("From")).toBeInTheDocument();
    expect(screen.getByText("To")).toBeInTheDocument();
    expect(screen.getByLabelText("From")).toHaveAttribute("type", "date");
    expect(screen.getByLabelText("To")).toHaveAttribute("type", "date");

    // The action filter stays free-text but suggests the known actions.
    const actionInput = screen.getByPlaceholderText(ACTION_PLACEHOLDER);
    const listId = actionInput.getAttribute("list");
    expect(listId).toBeTruthy();
    const datalist = document.getElementById(listId!);
    expect(datalist?.tagName).toBe("DATALIST");
    const options = [...(datalist?.querySelectorAll("option") ?? [])].map(
      (o) => o.value,
    );
    expect(options).toContain("report.resolved");
    expect(options).toHaveLength(7);
  });

  it("treats From/To as whole local days — To includes the selected day itself", async () => {
    setCurrentUser(MOCK_USERS.admin);
    renderApp("/admin/audit");

    expect(await screen.findByText("user.blocked")).toBeInTheDocument();

    // Anchor on the seeded entry itself (admin blocked mallory, mocks/db.ts)
    // rather than recomputing its timestamp, which could drift across a
    // midnight boundary between seeding and this test.
    const seededAt = new Date(
      db.audit.find((entry) => entry.action === "user.blocked")!.createdAt,
    );
    const entryDay = localDateValue(seededAt);
    const dayBefore = localDateValue(
      new Date(seededAt.getTime() - 24 * 60 * 60 * 1000),
    );

    // A To day before the entry's local day filters it out...
    fireEvent.change(screen.getByLabelText("To"), {
      target: { value: dayBefore },
    });
    await waitFor(() =>
      expect(screen.queryByText("user.blocked")).not.toBeInTheDocument(),
    );

    // ...and a From/To range of exactly the entry's local day includes it —
    // To covers the whole selected day, not just its first instant.
    fireEvent.change(screen.getByLabelText("From"), {
      target: { value: entryDay },
    });
    fireEvent.change(screen.getByLabelText("To"), {
      target: { value: entryDay },
    });
    expect(await screen.findByText("user.blocked")).toBeInTheDocument();
  });

  it("clears every filter and restores the unfiltered list", async () => {
    setCurrentUser(MOCK_USERS.admin);
    const user = userEvent.setup();
    renderApp("/admin/audit");

    expect(await screen.findByText("user.blocked")).toBeInTheDocument();

    // Filter by an action that matches nothing — the seeded row disappears.
    const actionInput = screen.getByPlaceholderText(ACTION_PLACEHOLDER);
    await user.type(actionInput, "message.created");
    await waitFor(() =>
      expect(screen.queryByText("user.blocked")).not.toBeInTheDocument(),
    );

    await user.click(screen.getByRole("button", { name: "Clear filters" }));

    expect(actionInput).toHaveValue("");
    expect(screen.getByPlaceholderText("Actor")).toHaveValue("");
    expect(screen.getByLabelText("From")).toHaveValue("");
    expect(screen.getByLabelText("To")).toHaveValue("");
    expect(await screen.findByText("user.blocked")).toBeInTheDocument();
  });
});

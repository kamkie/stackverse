// Moderation queue: reported bookmarks render with real context (title + url)
// when the contract lets the caller read them, and fall back to the raw id
// when it does not — GET /api/v1/bookmarks/{id} answers 404 for private,
// hidden, or deleted bookmarks even for moderators (existence is not
// disclosed), so that fallback is an expected state, not an error.
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { db, nextId } from "../mocks/db";
import { MOCK_USERS, setCurrentUser } from "../mocks/state";
import { renderApp } from "./utils";

describe("reports queue", () => {
  it("shows the reported bookmark's title and url when it is readable", async () => {
    setCurrentUser(MOCK_USERS.moderator);
    renderApp("/admin/reports");

    // Both seeded open reports point at the same public, active bookmark.
    expect(await screen.findAllByText("Suspicious crypto site")).toHaveLength(2);
    const links = screen.getAllByRole("link", {
      name: "https://example.com/crypto",
    });
    expect(links).toHaveLength(2);
    expect(links[0]).toHaveAttribute("target", "_blank");
    // The comment column stays intact (e2e keys rows off comment text).
    expect(screen.getByText("Looks like a scam.")).toBeInTheDocument();
    // The actions column header is labelled for assistive tech only.
    expect(
      screen.getByRole("columnheader", { name: "Actions" }),
    ).toBeInTheDocument();
  });

  it("falls back to the full id when the bookmark is not readable", async () => {
    const hidden = db.bookmarks.find((b) => b.status === "hidden");
    if (!hidden) throw new Error("seed data lost its hidden bookmark");
    db.reports.push({
      id: nextId(),
      bookmarkId: hidden.id,
      reporter: "carol",
      reason: "spam",
      comment: "Points at a hidden bookmark.",
      status: "open",
      createdAt: new Date().toISOString(),
    });

    setCurrentUser(MOCK_USERS.moderator);
    renderApp("/admin/reports");

    expect(await screen.findByText(hidden.id)).toBeInTheDocument();
    expect(
      await screen.findByText(
        "Bookmark not accessible (private, hidden, or deleted)",
      ),
    ).toBeInTheDocument();
    // Rows for the readable bookmark still resolve to its title.
    expect(await screen.findAllByText("Suspicious crypto site")).toHaveLength(2);
  });

  it("shows the empty-state copy when the status filter matches nothing", async () => {
    const user = userEvent.setup();
    setCurrentUser(MOCK_USERS.moderator);
    renderApp("/admin/reports");
    await screen.findAllByText("Suspicious crypto site");

    // No report has been dismissed in the seed data.
    await user.selectOptions(screen.getByRole("combobox"), "dismissed");

    expect(
      await screen.findByText("No reports to review — the queue is clear."),
    ).toBeInTheDocument();
  });

  it("lets a moderator revise a decision: dismiss, then re-open (rule 14)", async () => {
    const user = userEvent.setup();
    setCurrentUser(MOCK_USERS.moderator);
    renderApp("/admin/reports");
    await screen.findAllByText("Suspicious crypto site");

    // dismiss the first open report; it leaves the open queue
    await user.click(screen.getAllByRole("button", { name: "Dismiss" })[0] as HTMLElement);
    expect(await screen.findAllByText("Suspicious crypto site")).toHaveLength(1);

    // the dismissed view offers the revision actions
    await user.selectOptions(screen.getByRole("combobox"), "dismissed");
    expect(
      await screen.findByText("Dismissed", { selector: ".sv-badge" }),
    ).toBeInTheDocument();
    await user.click(await screen.findByRole("button", { name: "Re-open" }));

    // re-opened: gone from dismissed, back in the open queue
    expect(
      await screen.findByText("No reports to review — the queue is clear."),
    ).toBeInTheDocument();
    await user.selectOptions(screen.getByRole("combobox"), "open");
    expect(await screen.findAllByText("Suspicious crypto site")).toHaveLength(2);
  });
});

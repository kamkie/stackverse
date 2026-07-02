// Cursor pagination on GET /api/v2/bookmarks: "load more" appends the next
// slice using the opaque nextCursor and disappears on the last page.
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { db } from "../mocks/db";
import { MOCK_USERS, setCurrentUser } from "../mocks/state";
import { renderApp } from "./utils";

describe("cursor pagination", () => {
  it('loads the next slice on "load more" and stops on the last page', async () => {
    setCurrentUser(MOCK_USERS.demo);
    const total = db.bookmarks.filter((b) => b.owner === "demo").length;
    expect(total).toBeGreaterThan(20); // fixture guarantees more than one page

    const user = userEvent.setup();
    renderApp("/bookmarks");

    // First page: default size 20, more available.
    const loadMore = await screen.findByRole("button", { name: "Load more" });
    expect(screen.getAllByRole("heading", { level: 3 })).toHaveLength(20);

    await user.click(loadMore);

    await screen.findByRole("heading", { level: 3, name: "Reading list #1" });
    expect(screen.getAllByRole("heading", { level: 3 })).toHaveLength(total);
    expect(screen.queryByRole("button", { name: "Load more" })).not.toBeInTheDocument();
  });

  it("shows the moderation flag on hidden bookmarks in the owner's list", async () => {
    setCurrentUser(MOCK_USERS.demo);
    renderApp("/bookmarks");

    expect(await screen.findByText("Hidden by moderation")).toBeInTheDocument();
  });
});

// Admin user directory: the admin cannot block their own account (the API
// rejects self-block with 409, so the button is not offered), and the search
// box uses user-directory copy instead of the bookmark-search copy.
import { screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { MOCK_USERS, setCurrentUser } from "../mocks/state";
import { renderApp } from "./utils";

function rowOf(username: string): HTMLElement {
  const cell = screen.getByRole("cell", { name: username });
  const row = cell.closest("tr");
  if (!row) throw new Error(`no table row for ${username}`);
  return row;
}

describe("admin user directory", () => {
  it("offers Block on other users' rows but not on the admin's own row", async () => {
    setCurrentUser(MOCK_USERS.admin);
    renderApp("/admin/users");

    // Wait for both the table and the identity (`/me`) to settle: another
    // user's Block button appears only once the rows render, and the admin's
    // row hides its button based on the fetched caller identity.
    expect(
      await screen.findByText("admin", { selector: ".sv-username" }),
    ).toBeInTheDocument();
    const demoRow = (await screen.findByRole("cell", { name: "demo" })).closest("tr");
    expect(demoRow).not.toBeNull();
    expect(
      await within(demoRow as HTMLElement).findByRole("button", { name: "Block" }),
    ).toBeInTheDocument();

    const adminRow = rowOf("admin");
    expect(
      within(adminRow).queryByRole("button", { name: "Block" }),
    ).not.toBeInTheDocument();

    // Blocked users still get Unblock regardless of who is looking.
    expect(
      within(rowOf("mallory")).getByRole("button", { name: "Unblock" }),
    ).toBeInTheDocument();
  });

  it("labels the search box with user-directory copy", async () => {
    setCurrentUser(MOCK_USERS.admin);
    renderApp("/admin/users");

    const search = await screen.findByRole("searchbox", {
      name: "Search by username...",
    });
    expect(search).toHaveAttribute("placeholder", "Search by username...");
  });
});

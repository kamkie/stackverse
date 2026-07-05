// Admin user directory: the admin cannot block their own account (the API
// rejects self-block with 409, so the button is not offered), block/unblock
// mutations refresh the directory, and the search box uses user-directory copy.
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
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

  it("requires a reason before blocking a user", async () => {
    setCurrentUser(MOCK_USERS.admin);
    const user = userEvent.setup();
    renderApp("/admin/users");

    await screen.findByRole("cell", { name: "demo" });
    const demoRow = rowOf("demo");
    await user.click(
      await within(demoRow).findByRole("button", { name: "Block" }),
    );

    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: "Block" }));

    expect(
      await within(dialog).findByText("A reason is required when blocking a user."),
    ).toBeInTheDocument();
    expect(within(dialog).getByLabelText("Reason")).toHaveAttribute(
      "aria-invalid",
      "true",
    );
    expect(within(rowOf("demo")).getByText("Active")).toBeInTheDocument();
  });

  it("blocks another user with a reason and refreshes the row", async () => {
    setCurrentUser(MOCK_USERS.admin);
    const user = userEvent.setup();
    renderApp("/admin/users");

    await screen.findByRole("cell", { name: "demo" });
    const demoRow = rowOf("demo");
    await user.click(
      await within(demoRow).findByRole("button", { name: "Block" }),
    );

    const dialog = await screen.findByRole("dialog");
    expect(
      within(dialog).getByRole("heading", { name: "Block — demo" }),
    ).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText("Reason"), "Shared spam account.");
    await user.click(within(dialog).getByRole("button", { name: "Block" }));

    await waitFor(() =>
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument(),
    );
    const refreshedRow = rowOf("demo");
    const blockedBadge = within(refreshedRow).getByText("Blocked");
    expect(blockedBadge).toHaveAttribute("title", "Shared spam account.");
    expect(
      within(refreshedRow).getByRole("button", { name: "Unblock" }),
    ).toBeInTheDocument();
  });

  it("unblocks an existing blocked user and offers Block again", async () => {
    setCurrentUser(MOCK_USERS.admin);
    const user = userEvent.setup();
    renderApp("/admin/users");

    await screen.findByRole("cell", { name: "mallory" });
    const malloryRow = rowOf("mallory");
    expect(within(malloryRow).getByText("Blocked")).toHaveAttribute(
      "title",
      "Repeated spam.",
    );

    await user.click(
      await within(malloryRow).findByRole("button", { name: "Unblock" }),
    );

    await waitFor(() =>
      expect(within(rowOf("mallory")).getByText("Active")).toBeInTheDocument(),
    );
    expect(
      within(rowOf("mallory")).getByRole("button", { name: "Block" }),
    ).toBeInTheDocument();
  });
});

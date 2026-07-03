// Admin messages backoffice: destructive deletes are confirmed, writes give
// toast feedback, the language pickers are constrained selects, the exact-key
// filter is labeled as such, and a zero-match filter shows an empty state
// instead of a header-only table.
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { MOCK_USERS, setCurrentUser } from "../mocks/state";
import { renderApp } from "./utils";

describe("admin messages page", () => {
  it("deletes only after confirmation, then shows a toast", async () => {
    setCurrentUser(MOCK_USERS.admin);
    const user = userEvent.setup();
    renderApp("/admin/messages");

    // The seed ships this key in both languages: two rows on the first page.
    const cells = await screen.findAllByText("error.account.blocked", {
      selector: ".sv-cell-mono",
    });
    expect(cells).toHaveLength(2);

    const row = cells[0]?.closest("tr");
    expect(row).not.toBeNull();
    await user.click(within(row as HTMLElement).getByRole("button", { name: "Delete" }));

    // Clicking Delete opens a confirmation instead of mutating directly.
    const dialog = await screen.findByRole("dialog");
    expect(
      within(dialog).getByRole("heading", {
        name: "Delete — error.account.blocked",
      }),
    ).toBeInTheDocument();
    expect(
      within(dialog).getByText("Delete this message? This cannot be undone."),
    ).toBeInTheDocument();
    expect(
      screen.getAllByText("error.account.blocked", { selector: ".sv-cell-mono" }),
    ).toHaveLength(2);

    await user.click(within(dialog).getByRole("button", { name: "Delete" }));

    expect(await screen.findByText("Message deleted.")).toBeInTheDocument();
    await waitFor(() =>
      expect(
        screen.getAllByText("error.account.blocked", { selector: ".sv-cell-mono" }),
      ).toHaveLength(1),
    );
  });

  it("shows a toast after creating a message and closes the dialog", async () => {
    setCurrentUser(MOCK_USERS.admin);
    const user = userEvent.setup();
    renderApp("/admin/messages");

    await user.click(await screen.findByRole("button", { name: "Add" }));
    await screen.findByRole("heading", { name: "Add message" });

    await user.type(screen.getByLabelText("Key"), "ui.test.created-by-test");
    await user.type(screen.getByLabelText("Text"), "Created by test");
    await user.click(screen.getByRole("button", { name: "Save" }));

    expect(await screen.findByText("Message created.")).toBeInTheDocument();
    await waitFor(() =>
      expect(
        screen.queryByRole("heading", { name: "Add message" }),
      ).not.toBeInTheDocument(),
    );
  });

  it("offers the supported languages in a select instead of a free-text input", async () => {
    setCurrentUser(MOCK_USERS.admin);
    const user = userEvent.setup();
    renderApp("/admin/messages");

    await user.click(await screen.findByRole("button", { name: "Add" }));
    // Scoped to the dialog: the toolbar's language filter shares the name.
    const dialog = await screen.findByRole("dialog");
    const select = within(dialog).getByLabelText("Language");

    expect(select.tagName).toBe("SELECT");
    const options = within(select).getAllByRole("option") as HTMLOptionElement[];
    expect(options.map((option) => option.value)).toEqual(["en", "pl"]);
    expect(select).toHaveValue("en");
  });

  it("labels the language filter's match-all option", async () => {
    setCurrentUser(MOCK_USERS.admin);
    renderApp("/admin/messages");

    const option = (await screen.findByRole("option", {
      name: "All languages",
    })) as HTMLOptionElement;
    expect(option.value).toBe("");
    expect(option.selected).toBe(true);
  });

  it("shows an empty state instead of a header-only table when nothing matches", async () => {
    setCurrentUser(MOCK_USERS.admin);
    const user = userEvent.setup();
    renderApp("/admin/messages");

    const filter = await screen.findByLabelText("Exact key (e.g. ui.app.title)");
    await user.type(filter, "ui.no.such-key");

    expect(
      await screen.findByText("No messages match the current filters.", undefined, {
        timeout: 3000,
      }),
    ).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });
});

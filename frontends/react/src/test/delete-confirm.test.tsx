// Destructive-action safety: deleting a bookmark requires an explicit
// confirmation dialog — the card's Delete button alone never mutates.
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { db } from "../mocks/db";
import { MOCK_USERS, setCurrentUser } from "../mocks/state";
import { renderApp } from "./utils";

const TITLE = "Reading list #23";
const CONFIRM_BODY = "Delete this bookmark? This cannot be undone.";

async function openDeleteDialog(user: ReturnType<typeof userEvent.setup>) {
  const heading = await screen.findByRole("heading", { level: 3, name: TITLE });
  const card = heading.closest("li");
  expect(card).not.toBeNull();
  await user.click(within(card as HTMLElement).getByRole("button", { name: "Delete" }));
  const body = await screen.findByText(CONFIRM_BODY);
  return body.closest("dialog") as HTMLElement;
}

describe("bookmark delete confirmation", () => {
  it("does not delete on the card button alone, and cancel aborts", async () => {
    setCurrentUser(MOCK_USERS.demo);
    const user = userEvent.setup();
    renderApp("/bookmarks");

    const dialog = await openDeleteDialog(user);

    // The dialog is open, contextually titled, and nothing was deleted yet.
    expect(
      within(dialog).getByRole("heading", { name: `Delete — ${TITLE}` }),
    ).toBeInTheDocument();
    expect(db.bookmarks.some((b) => b.title === TITLE)).toBe(true);

    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));

    expect(screen.queryByText(CONFIRM_BODY)).not.toBeInTheDocument();
    expect(db.bookmarks.some((b) => b.title === TITLE)).toBe(true);
    expect(screen.getByRole("heading", { level: 3, name: TITLE })).toBeInTheDocument();
  });

  it("deletes on confirm and shows a success toast", async () => {
    setCurrentUser(MOCK_USERS.demo);
    const user = userEvent.setup();
    renderApp("/bookmarks");

    const dialog = await openDeleteDialog(user);
    await user.click(within(dialog).getByRole("button", { name: "Delete" }));

    expect(await screen.findByText("Bookmark deleted.")).toBeInTheDocument();
    await waitFor(() => expect(db.bookmarks.some((b) => b.title === TITLE)).toBe(false));
    await waitFor(() =>
      expect(
        screen.queryByRole("heading", { level: 3, name: TITLE }),
      ).not.toBeInTheDocument(),
    );
    expect(screen.queryByText(CONFIRM_BODY)).not.toBeInTheDocument();
  });
});

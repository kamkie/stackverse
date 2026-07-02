// Bookmark-page UX details: filter-aware empty state, tags-format hint,
// contextual dialog titles, and the anonymous login prompt on /bookmarks.
import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { MOCK_USERS, setCurrentUser } from "../mocks/state";
import { renderApp } from "./utils";

describe("filtered empty state", () => {
  it("explains that filters matched nothing instead of claiming an empty account", async () => {
    setCurrentUser(MOCK_USERS.demo);
    const user = userEvent.setup();
    renderApp("/bookmarks");

    await screen.findByRole("heading", { level: 3, name: "Reading list #23" });
    await user.type(
      screen.getByRole("searchbox", { name: "Search title and notes..." }),
      "zzz-nothing-matches",
    );

    expect(
      await screen.findByText("No bookmarks match your search or filters.", undefined, {
        timeout: 3000,
      }),
    ).toBeInTheDocument();
    expect(
      screen.queryByText("No bookmarks yet — add your first one."),
    ).not.toBeInTheDocument();
  });
});

describe("bookmark form dialog", () => {
  it("has contextual titles and a tags format hint", async () => {
    setCurrentUser(MOCK_USERS.demo);
    const user = userEvent.setup();
    renderApp("/bookmarks");

    await user.click(await screen.findByRole("button", { name: "Add" }));
    expect(await screen.findByRole("heading", { name: "Add bookmark" })).toBeInTheDocument();
    expect(
      screen.getByText(
        "Separate tags with spaces or commas — lowercase letters, digits, dashes.",
      ),
    ).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Cancel" }));

    const heading = await screen.findByRole("heading", {
      level: 3,
      name: "Reading list #23",
    });
    const card = heading.closest("li");
    expect(card).not.toBeNull();
    await user.click(within(card as HTMLElement).getByRole("button", { name: "Edit" }));
    expect(await screen.findByRole("heading", { name: "Edit bookmark" })).toBeInTheDocument();
  });
});

describe("anonymous /bookmarks", () => {
  it("shows a login prompt instead of the search/Add toolbar", async () => {
    renderApp("/bookmarks");

    // The prompt sits in the page content (the header has its own login link).
    const main = await screen.findByRole("main");
    const login = await within(main).findByRole("link", { name: "Log in" });
    expect(login).toHaveAttribute("href", "/auth/login");

    expect(screen.queryByRole("button", { name: "Add" })).not.toBeInTheDocument();
    expect(screen.queryByRole("searchbox")).not.toBeInTheDocument();
  });
});

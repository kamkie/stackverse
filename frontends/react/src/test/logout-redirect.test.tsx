// Logging out navigates to the public feed — the only page an anonymous
// visitor can use — and the chrome flips back to its anonymous state.
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { MOCK_USERS, setCurrentUser } from "../mocks/state";
import { renderApp } from "./utils";

describe("logout redirect", () => {
  it("lands an authenticated user on the public feed with the login link", async () => {
    const user = userEvent.setup();
    setCurrentUser(MOCK_USERS.demo);
    renderApp("/bookmarks");

    expect(
      await screen.findByRole("link", { name: "My bookmarks" }),
    ).toBeInTheDocument();
    await user.click(await screen.findByRole("button", { name: "Log out" }));

    expect(
      await screen.findByRole("heading", { name: "Public feed" }),
    ).toBeInTheDocument();
    expect(await screen.findByRole("link", { name: "Log in" })).toBeInTheDocument();
    // The authenticated-only navigation entry is gone with the session.
    expect(
      screen.queryByRole("link", { name: "My bookmarks" }),
    ).not.toBeInTheDocument();
  });
});

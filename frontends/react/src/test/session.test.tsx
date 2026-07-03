// Session bootstrap: the SPA holds no auth state — it asks GET /auth/session
// on startup and renders login vs. username/logout from the answer.
import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { MOCK_USERS, setCurrentUser } from "../mocks/state";
import { renderApp } from "./utils";

describe("session bootstrap", () => {
  it("lands an anonymous visitor on the public feed from /", async () => {
    renderApp("/");

    expect(
      await screen.findByRole("heading", { name: "Public feed" }),
    ).toBeInTheDocument();
  });

  it("offers login (full-page redirect to /auth/login) when anonymous", async () => {
    renderApp("/feed");

    const login = await screen.findByRole("link", { name: "Log in" });
    expect(login).toHaveAttribute("href", "/auth/login");
    expect(screen.queryByRole("button", { name: "Log out" })).not.toBeInTheDocument();
  });

  it("shows the username and a logout action when authenticated", async () => {
    setCurrentUser(MOCK_USERS.demo);
    renderApp("/feed");

    expect(
      await screen.findByText("demo", { selector: ".sv-username" }),
    ).toBeInTheDocument();
    expect(await screen.findByRole("button", { name: "Log out" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Log in" })).not.toBeInTheDocument();
  });
});

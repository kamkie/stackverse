// Blocked accounts (docs/SPEC.md rule 17): every authenticated call returns
// a 403 problem document with a localized detail, while the anonymous public
// surface — message bundle included — keeps working.
import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { db } from "../mocks/db";
import { MOCK_USERS, setCurrentUser } from "../mocks/state";
import { renderApp } from "./utils";

describe("blocked account", () => {
  it("gets a localized 403 on authenticated calls while public reads keep working", async () => {
    setCurrentUser(MOCK_USERS.demo);
    const account = db.users.find((u) => u.username === "demo");
    expect(account).toBeDefined();
    if (account) {
      account.status = "blocked";
      account.blockedReason = "Repeated spam.";
    }

    renderApp("/bookmarks");

    // The message bundle (public read) still loads: the chrome renders.
    expect(
      await screen.findByRole("link", { name: "My bookmarks" }),
    ).toBeInTheDocument();
    // The authenticated bookmarks call got the localized 403 problem detail.
    expect(
      await screen.findByText("Your account has been blocked."),
    ).toBeInTheDocument();
  });
});

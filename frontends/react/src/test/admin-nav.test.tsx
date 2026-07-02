// Role-gated admin navigation, driven by roles from GET /api/v1/me:
// regular users see no admin entry, moderators see dashboard + reports,
// admins see everything.
import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { MOCK_USERS, setCurrentUser } from "../mocks/state";
import { renderApp } from "./utils";

describe("role-gated admin navigation", () => {
  it("hides the admin section from regular users", async () => {
    setCurrentUser(MOCK_USERS.demo);
    renderApp("/feed");

    // Wait for the identity to settle (username rendered), then check the nav.
    expect(
      await screen.findByText("demo", { selector: ".sv-username" }),
    ).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Admin" })).not.toBeInTheDocument();
  });

  it("shows moderators only the dashboard and the reports queue", async () => {
    setCurrentUser(MOCK_USERS.moderator);
    renderApp("/admin");

    expect(await screen.findByRole("link", { name: "Admin" })).toBeInTheDocument();
    const adminNav = await screen.findByRole("navigation", { name: "Admin" });
    expect(await screen.findByRole("link", { name: "Dashboard" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Reports" })).toBeInTheDocument();
    expect(adminNav).not.toBeUndefined();
    expect(screen.queryByRole("link", { name: "Users" })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Audit log" })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Messages" })).not.toBeInTheDocument();
  });

  it("shows admins the full backoffice", async () => {
    setCurrentUser(MOCK_USERS.admin);
    renderApp("/admin");

    expect(await screen.findByRole("link", { name: "Dashboard" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Reports" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Users" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Audit log" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Messages" })).toBeInTheDocument();
  });
});

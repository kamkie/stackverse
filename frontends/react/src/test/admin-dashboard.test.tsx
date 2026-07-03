// Admin dashboard: the open-reports stat links to the reports queue and its
// label pluralizes with the count; the 30-day chart is labeled and its bars
// carry self-explanatory localized tooltips.
import { screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { db } from "../mocks/db";
import { MOCK_USERS, setCurrentUser } from "../mocks/state";
import { renderApp } from "./utils";

describe("admin dashboard", () => {
  it("renders the open-reports stat as a link to the reports queue", async () => {
    setCurrentUser(MOCK_USERS.admin);
    renderApp("/admin");

    // Seed data has 2 open reports — plural label, and the whole stat is a link.
    const stat = await screen.findByRole("link", { name: /Open reports/ });
    expect(stat).toHaveAttribute("href", "/admin/reports");
    expect(stat).toHaveClass("sv-stat", "sv-stat--link");
    expect(within(stat).getByText("2")).toHaveClass("sv-stat-value");
    expect(within(stat).getByText("Open reports")).toHaveClass("sv-stat-label");

    // The other stats stay plain (exactly one linked stat), and the e2e
    // contract of five .sv-stat cards survives the special-casing.
    expect(document.querySelectorAll(".sv-stat")).toHaveLength(5);
    expect(document.querySelectorAll("a.sv-stat")).toHaveLength(1);
  });

  it("uses the singular label when exactly one report is open", async () => {
    // Stats derive from db state; drop one of the two seeded open reports.
    db.reports.pop();
    setCurrentUser(MOCK_USERS.admin);
    renderApp("/admin");

    const stat = await screen.findByRole("link", { name: /Open report$/ });
    expect(stat).toHaveAttribute("href", "/admin/reports");
    expect(within(stat).getByText("1")).toHaveClass("sv-stat-value");
    expect(within(stat).getByText("Open report")).toHaveClass("sv-stat-label");
  });

  it("labels the chart and gives each bar group a localized tooltip", async () => {
    setCurrentUser(MOCK_USERS.admin);
    renderApp("/admin");

    const chart = await screen.findByRole("img", { name: "Last 30 days" });
    const title = chart.querySelector("g > title");
    expect(title?.textContent).toMatch(
      /^\d{4}-\d{2}-\d{2}: \d+ Bookmarks created, \d+ Active users$/,
    );
  });
});

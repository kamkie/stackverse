// My reports (frontends/README.md, required screens): the reporter's feedback
// loop — own reports with their moderation status, revisable and withdrawable
// while open.
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { db } from "../mocks/db";
import { MOCK_USERS, setCurrentUser } from "../mocks/state";
import { renderApp } from "./utils";

// The seed ships exactly one open report, filed by demo on the crypto bookmark.
const REPORTED_TITLE = "Suspicious crypto site";

describe("my reports page", () => {
  it("lists the caller's reports with the reported bookmark and status", async () => {
    setCurrentUser(MOCK_USERS.demo);
    renderApp("/reports");

    const title = await screen.findByText(REPORTED_TITLE);
    const row = title.closest("tr");
    expect(row).not.toBeNull();
    expect(within(row as HTMLElement).getByText("Open")).toBeInTheDocument();
    expect(within(row as HTMLElement).getByText("Spam")).toBeInTheDocument();
  });

  it("revises an open report through the edit dialog", async () => {
    setCurrentUser(MOCK_USERS.demo);
    const user = userEvent.setup();
    renderApp("/reports");

    const title = await screen.findByText(REPORTED_TITLE);
    const row = title.closest("tr") as HTMLElement;
    await user.click(within(row).getByRole("button", { name: "Edit" }));

    const dialog = await screen.findByRole("dialog");
    await user.selectOptions(within(dialog).getByLabelText("Reason"), "other");
    await user.clear(within(dialog).getByLabelText("Comment"));
    await user.type(within(dialog).getByLabelText("Comment"), "second thoughts");
    await user.click(within(dialog).getByRole("button", { name: "Save" }));

    expect(await screen.findByText("Report updated.")).toBeInTheDocument();
    await waitFor(() =>
      expect(within(row).getByText("Other")).toBeInTheDocument(),
    );
    expect(within(row).getByText("second thoughts")).toBeInTheDocument();
  });

  it("withdraws an open report only after confirmation", async () => {
    setCurrentUser(MOCK_USERS.demo);
    const user = userEvent.setup();
    // the feed remembers reported bookmarks for the session — withdrawal
    // frees the slot, so the marker must go too
    const reportedBookmarkId = db.reports[0]?.bookmarkId as string;
    sessionStorage.setItem("stackverse.reported", JSON.stringify([reportedBookmarkId]));
    renderApp("/reports");

    const title = await screen.findByText(REPORTED_TITLE);
    const row = title.closest("tr") as HTMLElement;
    await user.click(within(row).getByRole("button", { name: "Withdraw" }));

    // Clicking Withdraw opens a confirmation instead of mutating directly.
    const dialog = await screen.findByRole("dialog");
    expect(
      within(dialog).getByText(
        "Withdraw this report? Moderators will no longer see it.",
      ),
    ).toBeInTheDocument();
    expect(screen.getByText(REPORTED_TITLE)).toBeInTheDocument();

    await user.click(within(dialog).getByRole("button", { name: "Withdraw" }));

    expect(await screen.findByText("Report withdrawn.")).toBeInTheDocument();
    expect(
      await screen.findByText(
        "No reports yet — anything you report shows up here.",
      ),
    ).toBeInTheDocument();
    expect(
      JSON.parse(sessionStorage.getItem("stackverse.reported") ?? "[]"),
    ).not.toContain(reportedBookmarkId);
  });
});

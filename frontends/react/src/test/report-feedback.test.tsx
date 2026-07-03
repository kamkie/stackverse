// Report flow feedback: a successful report shows a success toast and flips
// the card's action to a disabled "Reported" state for the session. A 409
// duplicate is positive proof the same state already exists (SPEC rule 13),
// so it confirms identically instead of erroring (frontends/README.md #2).
import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { MOCK_USERS, setCurrentUser } from "../mocks/state";
import { renderApp } from "./utils";

// carol's public bookmark — demo has no pre-seeded open report on it.
const TITLE = "CSS grid garden";
// carol's other public bookmark — demo's open report on it is pre-seeded,
// so submitting another one answers 409.
const REPORTED_TITLE = "Suspicious crypto site";

describe("report flow feedback", () => {
  it("shows a toast and a disabled Reported state after a successful report", async () => {
    setCurrentUser(MOCK_USERS.demo);
    const user = userEvent.setup();
    renderApp("/feed");

    const heading = await screen.findByRole("heading", { level: 3, name: TITLE });
    const card = heading.closest("li");
    expect(card).not.toBeNull();
    await user.click(within(card as HTMLElement).getByRole("button", { name: "Report" }));

    // The dialog names the bookmark being reported.
    const dialogTitle = await screen.findByRole("heading", {
      name: `Report — ${TITLE}`,
    });
    const dialog = dialogTitle.closest("dialog");
    expect(dialog).not.toBeNull();
    await user.click(
      within(dialog as HTMLElement).getByRole("button", { name: "Report" }),
    );

    expect(
      await screen.findByText("Report submitted — thank you."),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("heading", { name: `Report — ${TITLE}` }),
    ).not.toBeInTheDocument();

    const reported = await within(card as HTMLElement).findByRole("button", {
      name: "Reported",
    });
    expect(reported).toBeDisabled();
    expect(
      within(card as HTMLElement).queryByRole("button", { name: "Report" }),
    ).not.toBeInTheDocument();
  });

  it("treats a 409 duplicate as confirmation: toast, dialog closed, Reported state", async () => {
    setCurrentUser(MOCK_USERS.demo);
    const user = userEvent.setup();
    renderApp("/feed");

    const heading = await screen.findByRole("heading", {
      level: 3,
      name: REPORTED_TITLE,
    });
    const card = heading.closest("li");
    expect(card).not.toBeNull();
    await user.click(within(card as HTMLElement).getByRole("button", { name: "Report" }));

    const dialogTitle = await screen.findByRole("heading", {
      name: `Report — ${REPORTED_TITLE}`,
    });
    const dialog = dialogTitle.closest("dialog");
    expect(dialog).not.toBeNull();
    await user.click(
      within(dialog as HTMLElement).getByRole("button", { name: "Report" }),
    );

    expect(
      await screen.findByText("Already reported — your earlier report is still open."),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("heading", { name: `Report — ${REPORTED_TITLE}` }),
    ).not.toBeInTheDocument();

    const reported = await within(card as HTMLElement).findByRole("button", {
      name: "Reported",
    });
    expect(reported).toBeDisabled();
    expect(
      within(card as HTMLElement).queryByRole("button", { name: "Report" }),
    ).not.toBeInTheDocument();
  });
});

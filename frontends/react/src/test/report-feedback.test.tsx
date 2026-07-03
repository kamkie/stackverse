// Report flow feedback: a successful report shows a success toast and flips
// the card's action to a disabled "Reported" state for the session.
import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { MOCK_USERS, setCurrentUser } from "../mocks/state";
import { renderApp } from "./utils";

// carol's public bookmark — demo has no pre-seeded open report on it.
const TITLE = "CSS grid garden";

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
});

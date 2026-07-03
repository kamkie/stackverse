// Audit log toolbar: the date-range inputs carry visible From/To labels,
// the action filter offers known-action suggestions but stays free-text,
// and "Clear filters" resets every filter and restores the unfiltered list.
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { MOCK_USERS, setCurrentUser } from "../mocks/state";
import { renderApp } from "./utils";

const ACTION_PLACEHOLDER = "Exact action, e.g. report.resolved";

describe("audit log filters", () => {
  it("renders labeled date fields and known-action suggestions", async () => {
    setCurrentUser(MOCK_USERS.admin);
    renderApp("/admin/audit");

    // The single seeded audit entry (admin blocked mallory) is listed.
    expect(await screen.findByText("user.blocked")).toBeInTheDocument();

    // Visible text labels, associated with the date inputs by wrapping.
    expect(screen.getByText("From")).toBeInTheDocument();
    expect(screen.getByText("To")).toBeInTheDocument();
    expect(screen.getByLabelText("From")).toHaveAttribute("type", "date");
    expect(screen.getByLabelText("To")).toHaveAttribute("type", "date");

    // The action filter stays free-text but suggests the known actions.
    const actionInput = screen.getByPlaceholderText(ACTION_PLACEHOLDER);
    const listId = actionInput.getAttribute("list");
    expect(listId).toBeTruthy();
    const datalist = document.getElementById(listId!);
    expect(datalist?.tagName).toBe("DATALIST");
    const options = [...(datalist?.querySelectorAll("option") ?? [])].map(
      (o) => o.value,
    );
    expect(options).toContain("report.resolved");
    expect(options).toHaveLength(7);
  });

  it("clears every filter and restores the unfiltered list", async () => {
    setCurrentUser(MOCK_USERS.admin);
    const user = userEvent.setup();
    renderApp("/admin/audit");

    expect(await screen.findByText("user.blocked")).toBeInTheDocument();

    // Filter by an action that matches nothing — the seeded row disappears.
    const actionInput = screen.getByPlaceholderText(ACTION_PLACEHOLDER);
    await user.type(actionInput, "message.created");
    await waitFor(() =>
      expect(screen.queryByText("user.blocked")).not.toBeInTheDocument(),
    );

    await user.click(screen.getByRole("button", { name: "Clear filters" }));

    expect(actionInput).toHaveValue("");
    expect(screen.getByPlaceholderText("Actor")).toHaveValue("");
    expect(screen.getByLabelText("From")).toHaveValue("");
    expect(screen.getByLabelText("To")).toHaveValue("");
    expect(await screen.findByText("user.blocked")).toBeInTheDocument();
  });
});

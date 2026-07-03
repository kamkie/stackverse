// Dev-only action logging (src/dev/logUserActions.ts): clicks, submits,
// navigation, and API outcomes must land on console.debug so the dev-server
// forwarder can trace them — and field values must never appear
// (docs/LOGGING.md §6, §9).
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {
  beforeAll,
  beforeEach,
  describe,
  expect,
  it,
  vi,
  type MockInstance,
} from "vitest";
import { api } from "../api/client";
import { ConfirmDialog } from "../components/ConfirmDialog";
import { logUserActions } from "../dev/logUserActions";
import { MOCK_USERS, setCurrentUser } from "../mocks/state";

// After setup.ts's server.listen(), which swaps globalThis.fetch for MSW's —
// installing at import time would leave the logger's fetch wrapper orphaned.
beforeAll(() => {
  logUserActions();
});

let debugSpy: MockInstance;

beforeEach(() => {
  debugSpy = vi.spyOn(console, "debug").mockImplementation(() => {});
});

function messages(): string[] {
  return debugSpy.mock.calls.map((call) => String(call[0]));
}

describe("dev action log", () => {
  it("logs a click as the nearest interactive element", async () => {
    render(
      <button type="button" aria-label="Add bookmark">
        <span data-testid="icon">+</span>
      </button>,
    );

    await userEvent.click(screen.getByTestId("icon"));

    expect(messages()).toContainEqual(
      expect.stringContaining('[action] click button "Add bookmark"'),
    );
  });

  it("marks clicks that hit nothing interactive", async () => {
    render(<div data-testid="dead-zone">nothing here</div>);

    await userEvent.click(screen.getByTestId("dead-zone"));

    expect(messages()).toContainEqual(
      expect.stringContaining("(non-interactive)"),
    );
  });

  it("logs form submits with the submitter", async () => {
    render(
      <form aria-label="new bookmark" onSubmit={(e) => e.preventDefault()}>
        <button type="submit">Save</button>
      </form>,
    );

    await userEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(messages()).toContainEqual(
      expect.stringContaining('[action] submit form "new bookmark" via button "Save"'),
    );
  });

  it("appends the data-ctx ancestor chain, so row actions name their row", async () => {
    render(
      <table>
        <tbody>
          <tr data-ctx="report:123">
            <td>
              <button type="button">Dismiss</button>
            </td>
          </tr>
        </tbody>
      </table>,
    );

    await userEvent.click(screen.getByRole("button", { name: "Dismiss" }));

    expect(messages()).toContainEqual(
      expect.stringContaining('click button "Dismiss" in report:123 @'),
    );
  });

  it("dialog actions carry the dialog's ctx", async () => {
    render(
      <ConfirmDialog
        title="Delete"
        body="Sure?"
        confirmLabel="Delete"
        cancelLabel="Cancel"
        ctx="bookmark:42"
        onConfirm={() => {}}
        onClose={() => {}}
      />,
    );

    await userEvent.click(screen.getByRole("button", { name: "Delete" }));

    expect(messages()).toContainEqual(
      expect.stringContaining('click button "Delete" in bookmark:42 @'),
    );
  });

  it("describes fields by name, never by value", async () => {
    render(<input name="url" defaultValue="https://secret.example/token" />);

    await userEvent.click(screen.getByRole("textbox"));

    const logged = messages();
    expect(logged).toContainEqual(
      expect.stringContaining('click input[type=text] "url"'),
    );
    expect(logged.some((m) => m.includes("secret"))).toBe(false);
  });

  it("logs API calls with method, path, and status", async () => {
    setCurrentUser(MOCK_USERS.demo);

    await api.GET("/api/v1/tags");

    expect(messages()).toContainEqual(
      expect.stringMatching(/^\[api\] GET \/api\/v1\/tags → 200 \(\d+ms\)$/),
    );
  });

  it("logs history navigation", () => {
    history.pushState(null, "", "/bookmarks?page=2");

    expect(messages()).toContainEqual(
      expect.stringMatching(/^\[nav\] push .* → \/bookmarks\?page=2$/),
    );
  });
});

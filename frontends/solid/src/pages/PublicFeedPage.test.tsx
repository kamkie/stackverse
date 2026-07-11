import { fireEvent, render, screen, waitFor } from "@solidjs/testing-library";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { setMe, setSession } from "../lib/session";
import { bookmark, jsonResponse, readyI18n } from "../test/fixtures";
import PublicFeedPage from "./PublicFeedPage";

beforeEach(() => {
  readyI18n();
  setSession({ authenticated: false });
  setMe(null);
});

describe("PublicFeedPage", () => {
  it("keeps reporting behind authentication and follows opaque cursors", async () => {
    const first = bookmark({ id: "first", title: "First" });
    const second = bookmark({ id: "second", title: "Second" });
    const fetchMock = vi.fn((input: URL) => {
      return Promise.resolve(
        jsonResponse(
          input.searchParams.get("cursor")
            ? { items: [second] }
            : { items: [first], nextCursor: "opaque-next" },
        ),
      );
    });
    vi.stubGlobal("fetch", fetchMock);
    render(() => <PublicFeedPage toast={vi.fn()} />);

    expect(await screen.findByText("First")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "report" })).toBeNull();
    await fireEvent.click(screen.getByRole("button", { name: "load-more" }));
    expect(await screen.findByText("Second")).toBeTruthy();
    const secondUrl = fetchMock.mock.calls[1][0] as URL;
    expect(secondUrl.pathname).toBe("/api/v2/bookmarks");
    expect(secondUrl.searchParams.get("cursor")).toBe("opaque-next");
    expect(secondUrl.searchParams.get("visibility")).toBe("public");
  });

  it("offers the report dialog to an authenticated session", async () => {
    setSession({ authenticated: true, username: "demo" });
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(jsonResponse({ items: [bookmark()] })),
    );
    render(() => <PublicFeedPage toast={vi.fn()} />);

    await fireEvent.click(await screen.findByRole("button", { name: "report" }));

    expect(screen.getByRole("dialog").dataset.ctx).toBe("bookmark:bookmark-1");
    expect(screen.getByLabelText("reason")).toBeTruthy();
  });

  it("renders API failures without replacing them with an empty result", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("feed offline")));
    render(() => <PublicFeedPage toast={vi.fn()} />);

    await waitFor(() =>
      expect(screen.getByRole("alert").textContent).toContain("feed offline"),
    );
  });
});

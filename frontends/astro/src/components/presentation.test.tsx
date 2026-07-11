import { fireEvent, render, screen, waitFor } from "@solidjs/testing-library";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { bookmark, jsonResponse, readyI18n } from "../test/fixtures";
import BookmarkCard from "./BookmarkCard";
import BookmarkContext from "./BookmarkContext";
import Pagination from "./Pagination";
import ToastRegion from "./ToastRegion";

beforeEach(() => {
  readyI18n();
});

describe("bookmark presentation", () => {
  it("renders owner-only controls and moderation state with entity context", async () => {
    const item = bookmark({ id: "hidden-1", status: "hidden" });
    const onEdit = vi.fn();
    const onDelete = vi.fn();
    render(() => (
      <BookmarkCard
        bookmark={item}
        mode="own"
        onEdit={onEdit}
        onDelete={onDelete}
      />
    ));

    expect(screen.getByText("hidden")).toBeTruthy();
    expect(document.querySelector("[data-ctx='bookmark:hidden-1']")).toBeTruthy();
    await fireEvent.click(screen.getByRole("button", { name: "edit" }));
    await fireEvent.click(screen.getByRole("button", { name: "delete" }));
    expect(onEdit).toHaveBeenCalledWith(item);
    expect(onDelete).toHaveBeenCalledWith(item);
    expect(screen.queryByRole("button", { name: "report" })).toBeNull();
  });

  it("keeps the report row usable when bookmark details are masked or unavailable", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        jsonResponse({ title: "Not found", status: 404 }, { status: 404 }),
      ),
    );
    render(() => <BookmarkContext bookmarkId="private-bookmark" />);

    expect(screen.getByText("private-bookmark")).toBeTruthy();
    await waitFor(() =>
      expect(screen.getByText("bookmark-unavailable")).toBeTruthy(),
    );
  });
});

describe("shared Solid components", () => {
  it("enforces pagination boundaries and emits only a valid next page", async () => {
    const onPage = vi.fn();
    render(() => <Pagination page={0} totalPages={3} onPage={onPage} />);

    expect((screen.getByRole("button", { name: "previous" }) as HTMLButtonElement).disabled).toBe(
      true,
    );
    await fireEvent.click(screen.getByRole("button", { name: "next" }));
    expect(onPage).toHaveBeenCalledExactlyOnceWith(1);
  });

  it("renders toast tones in one polite live region", () => {
    render(() => (
      <ToastRegion
        toasts={[
          { id: 1, message: "Saved", tone: "success" },
          { id: 2, message: "Failed", tone: "danger" },
        ]}
      />
    ));

    const region = screen.getByRole("status");
    expect(region.getAttribute("aria-live")).toBe("polite");
    expect(region.querySelector(".sv-toast--success")?.textContent).toBe("Saved");
    expect(region.querySelector(".sv-toast--danger")?.textContent).toBe("Failed");
  });
});

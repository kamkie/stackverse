// @vitest-environment node
import { createDOM } from "@builder.io/qwik/testing";
import { describe, expect, it } from "vitest";
import type { I18nState } from "../lib/i18n";
import type { Bookmark } from "../lib/types";
import BookmarkCard from "./BookmarkCard";

const i18n: I18nState = {
  lang: "en",
  resolvedLanguage: "en",
  ready: true,
  messages: {
    "ui.action.delete": "Delete",
    "ui.action.edit": "Edit",
    "ui.bookmark.hidden": "Hidden",
    "ui.visibility.public": "Public",
  },
};

function bookmark(overrides: Partial<Bookmark> = {}): Bookmark {
  return {
    id: "bookmark-1",
    owner: "demo",
    url: "https://example.com/qwik",
    title: "Qwik guide",
    notes: "Framework boundary notes",
    tags: ["qwik", "typescript"],
    visibility: "public",
    status: "hidden",
    createdAt: "2026-07-05T12:00:00Z",
    updatedAt: "2026-07-05T12:00:00Z",
    ...overrides,
  };
}

describe("BookmarkCard", () => {
  it("renders owner controls, moderation state, metadata, and safe links", async () => {
    const { render, screen } = await createDOM();

    await render(<BookmarkCard i18n={i18n} bookmark={bookmark()} mode="own" />);

    const card = screen.querySelector("[data-ctx='bookmark:bookmark-1']");
    expect(card?.textContent).toContain("Qwik guide");
    expect(card?.textContent).toContain("Hidden");
    expect(card?.textContent).toContain("Framework boundary notes");
    expect(card?.textContent).toContain("qwik");
    expect(card?.querySelector("time")?.getAttribute("datetime")).toBe(
      "2026-07-05T12:00:00Z",
    );
    expect(card?.querySelector("a")?.getAttribute("rel")).toBe("noreferrer");
    expect(
      Array.from(card?.querySelectorAll("button") ?? [], (button) =>
        button.textContent?.trim(),
      ),
    ).toEqual(["Edit", "Delete"]);
  });

  it("hides owner and report actions on an anonymous public-feed card", async () => {
    const { render, screen } = await createDOM();

    await render(
      <BookmarkCard
        i18n={i18n}
        bookmark={bookmark({ status: "active", notes: "", tags: [] })}
        mode="feed"
      />,
    );

    expect(screen.querySelector("button")).toBeFalsy();
    expect(screen.querySelector(".sv-badge--warning")).toBeFalsy();
    expect(screen.querySelector(".sv-bookmark-notes")).toBeFalsy();
    expect(screen.querySelector(".sv-tag-list")).toBeFalsy();
  });
});

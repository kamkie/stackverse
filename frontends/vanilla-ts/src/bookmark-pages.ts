import { apiGet } from "./api";
import { i18n, state } from "./app-state";
import type { BookmarkListState } from "./app-state";
import { renderedPage } from "./page-render";
import type { RenderedPage } from "./page-render";
import {
  t,
  escapeHtml,
  selected,
  pathForApi,
  readReportedIds,
  loginPromptHtml,
  paginationHtml,
} from "./view-helpers";
import type {
  Bookmark,
  BookmarkCursorPage,
  Page,
  Report,
  ReportStatus,
  TagList,
  Visibility,
} from "./types";

export function resetBookmarkList(list: BookmarkListState): void {
  list.generation += 1;
  delete list.pending;
}

export async function fetchNextBookmarks(
  list: BookmarkListState,
  visibility?: Visibility,
): Promise<void> {
  if (list.pending) return list.pending;
  const generation = list.generation;
  const replace = list.loadedGeneration !== generation;
  const tags = [...list.tags];
  const q = list.q;
  const cursor = replace ? undefined : list.nextCursor;
  const pending = (async () => {
    let page: BookmarkCursorPage;
    try {
      page = await apiGet<BookmarkCursorPage>("/api/v2/bookmarks", {
        ...(tags.length > 0 ? { tag: tags } : {}),
        ...(q ? { q } : {}),
        ...(visibility ? { visibility } : {}),
        ...(cursor ? { cursor } : {}),
      });
    } catch (error) {
      if (list.generation !== generation) return;
      throw error;
    }
    if (list.generation !== generation) return;
    if (replace) list.pages = [page];
    else list.pages.push(page);
    list.loadedGeneration = generation;
    if (page.nextCursor === undefined) delete list.nextCursor;
    else list.nextCursor = page.nextCursor;
  })();
  list.pending = pending;
  try {
    await pending;
  } finally {
    if (list.pending === pending) delete list.pending;
  }
}

async function ensureBookmarks(
  list: BookmarkListState,
  visibility?: Visibility,
): Promise<void> {
  if (list.loadedGeneration !== list.generation || list.pages.length === 0) {
    await fetchNextBookmarks(list, visibility);
  }
}

function allBookmarks(list: BookmarkListState): Bookmark[] {
  return list.pages.flatMap((page) => page.items);
}

export function findBookmark(
  id: string,
  listName: "bookmarks" | "feed",
): Bookmark | undefined {
  return allBookmarks(state[listName]).find((bookmark) => bookmark.id === id);
}

export function tagListHtml(
  tags: { tag: string; count?: number }[],
  activeTags: string[],
  listName: "bookmarks" | "feed",
  clickable = true,
): string {
  return `<ul class="sv-tag-list">${tags
    .map(
      ({ tag, count }) =>
        `<li><button type="button" class="sv-tag${activeTags.includes(tag) ? " is-active" : ""}" data-action="toggle-tag" data-list="${listName}" data-tag="${escapeHtml(tag)}"${clickable ? "" : " disabled"}>${escapeHtml(tag)}${count !== undefined ? ` <span class="sv-tag-count">${count}</span>` : ""}</button></li>`,
    )
    .join("")}</ul>`;
}

function bookmarkCardHtml(
  bookmark: Bookmark,
  list: "bookmarks" | "feed",
  actions = "",
): string {
  const activeTags =
    list === "bookmarks" ? state.bookmarks.tags : state.feed.tags;
  const tags = bookmark.tags.length
    ? tagListHtml(
        bookmark.tags.map((tag) => ({ tag })),
        activeTags,
        list,
      )
    : "";
  return `<li class="sv-card sv-bookmark" data-ctx="bookmark:${escapeHtml(bookmark.id)}">
    <div class="sv-bookmark-head">
      <h3 class="sv-bookmark-title"><a href="${escapeHtml(bookmark.url)}" target="_blank" rel="noopener noreferrer">${escapeHtml(bookmark.title)}</a></h3>
      ${bookmark.status === "hidden" ? `<span class="sv-badge sv-badge--warning">${escapeHtml(t("ui.bookmark.hidden"))}</span>` : ""}
      ${bookmark.visibility === "public" ? `<span class="sv-badge">${escapeHtml(t("ui.visibility.public"))}</span>` : ""}
    </div>
    <span class="sv-bookmark-url">${escapeHtml(bookmark.url)}</span>
    ${bookmark.notes ? `<p class="sv-bookmark-notes">${escapeHtml(bookmark.notes)}</p>` : ""}
    <div class="sv-bookmark-meta">
      ${tags}
      <span>${escapeHtml(bookmark.owner)}</span>
      <time datetime="${escapeHtml(bookmark.createdAt)}">${escapeHtml(new Date(bookmark.createdAt).toLocaleDateString(i18n.resolvedLanguage))}</time>
      ${actions ? `<div class="sv-bookmark-actions">${actions}</div>` : ""}
    </div>
  </li>`;
}

function bookmarkListHtml(
  list: BookmarkListState,
  listName: "bookmarks" | "feed",
  renderActions: (bookmark: Bookmark) => string,
  emptyMessage?: string,
): string {
  const bookmarks = allBookmarks(list);
  if (bookmarks.length === 0) {
    return `<div class="sv-empty">${escapeHtml(emptyMessage ?? t("ui.bookmarks.empty"))}</div>`;
  }
  return `<ul class="sv-card-list">${bookmarks
    .map((bookmark) =>
      bookmarkCardHtml(bookmark, listName, renderActions(bookmark)),
    )
    .join("")}</ul>
    ${
      list.nextCursor
        ? `<div class="sv-load-more"><button type="button" class="sv-button" data-action="load-more" data-list="${listName}">${escapeHtml(t("ui.action.load-more"))}</button></div>`
        : ""
    }`;
}

export async function myBookmarksPageHtml(): Promise<RenderedPage> {
  if (!state.session?.authenticated) {
    return renderedPage(
      `<section class="sv-content"><h1 class="sv-page-title">${escapeHtml(t("ui.nav.my-bookmarks"))}</h1>${loginPromptHtml()}</section>`,
    );
  }

  const [tags] = await Promise.all([
    apiGet<TagList>("/api/v1/tags"),
    ensureBookmarks(state.bookmarks),
  ]);
  const filtered = state.bookmarks.q !== "" || state.bookmarks.tags.length > 0;
  return renderedPage(`<div class="sv-layout">
    <aside class="sv-sidebar">
      <h2 class="sv-sidebar-title">${escapeHtml(t("ui.nav.tags"))}</h2>
      ${tagListHtml(tags.tags, state.bookmarks.tags, "bookmarks")}
    </aside>
    <section class="sv-content">
      <h1 class="sv-page-title">${escapeHtml(t("ui.nav.my-bookmarks"))}</h1>
      <div class="sv-toolbar">
        <input type="search" class="sv-input" placeholder="${escapeHtml(t("ui.bookmarks.search.placeholder"))}" aria-label="${escapeHtml(t("ui.bookmarks.search.placeholder"))}" data-bind="bookmarks-q" value="${escapeHtml(state.bookmarks.q)}">
        <button type="button" class="sv-button sv-button--primary" data-action="open-bookmark-create">${escapeHtml(t("ui.action.add"))}</button>
      </div>
      ${bookmarkListHtml(
        state.bookmarks,
        "bookmarks",
        (bookmark) =>
          `<button type="button" class="sv-button sv-button--ghost sv-button--sm" data-action="open-bookmark-edit" data-id="${escapeHtml(bookmark.id)}">${escapeHtml(t("ui.action.edit"))}</button>
           <button type="button" class="sv-button sv-button--ghost sv-button--sm" data-action="open-bookmark-delete" data-id="${escapeHtml(bookmark.id)}">${escapeHtml(t("ui.action.delete"))}</button>`,
        filtered ? t("ui.bookmarks.no-matches") : undefined,
      )}
    </section>
  </div>`);
}

export async function publicFeedPageHtml(): Promise<RenderedPage> {
  await ensureBookmarks(state.feed, "public");
  const reported = readReportedIds();
  const authenticated = state.session?.authenticated === true;
  const filtered = state.feed.q !== "" || state.feed.tags.length > 0;
  return renderedPage(`<section class="sv-content">
    <h1 class="sv-page-title">${escapeHtml(t("ui.nav.public-feed"))}</h1>
    <div class="sv-toolbar">
      <input type="search" class="sv-input" placeholder="${escapeHtml(t("ui.bookmarks.search.placeholder"))}" aria-label="${escapeHtml(t("ui.bookmarks.search.placeholder"))}" data-bind="feed-q" value="${escapeHtml(state.feed.q)}">
    </div>
    ${bookmarkListHtml(
      state.feed,
      "feed",
      (bookmark) =>
        authenticated
          ? reported.has(bookmark.id)
            ? `<button type="button" class="sv-button sv-button--ghost sv-button--sm" disabled>${escapeHtml(t("ui.report.reported"))}</button>`
            : `<button type="button" class="sv-button sv-button--ghost sv-button--sm" data-action="open-report" data-id="${escapeHtml(bookmark.id)}">${escapeHtml(t("ui.action.report"))}</button>`
          : "",
      filtered ? t("ui.bookmarks.no-matches") : undefined,
    )}
  </section>`);
}

export async function bookmarkContextMap(
  reports: Report[],
): Promise<Map<string, Bookmark | null>> {
  const entries = await Promise.all(
    reports.map(async (report) => {
      try {
        return [
          report.bookmarkId,
          await apiGet<Bookmark>(
            pathForApi("/api/v1/bookmarks", report.bookmarkId),
          ),
        ] as const;
      } catch {
        return [report.bookmarkId, null] as const;
      }
    }),
  );
  return new Map(entries);
}

export function bookmarkCellHtml(
  bookmarkId: string,
  contexts: Map<string, Bookmark | null>,
): string {
  const bookmark = contexts.get(bookmarkId);
  if (bookmark) {
    return `<strong>${escapeHtml(bookmark.title)}</strong>
      <div><a class="sv-bookmark-url" href="${escapeHtml(bookmark.url)}" target="_blank" rel="noreferrer">${escapeHtml(bookmark.url)}</a></div>`;
  }
  return `<span class="sv-cell-mono">${escapeHtml(bookmarkId)}</span>
    <div class="sv-field-hint">${escapeHtml(t("ui.reports.bookmark-unavailable"))}</div>`;
}

export async function myReportsPageHtml(): Promise<RenderedPage> {
  if (!state.session?.authenticated) {
    return renderedPage(
      `<section class="sv-content"><h1 class="sv-page-title">${escapeHtml(t("ui.nav.my-reports"))}</h1>${loginPromptHtml()}</section>`,
    );
  }
  const data = await apiGet<Page<Report>>("/api/v1/reports", {
    ...(state.myReports.status ? { status: state.myReports.status } : {}),
    page: state.myReports.page,
  });
  const contexts = await bookmarkContextMap(data.items);
  return renderedPage(
    `<h1 class="sv-page-title">${escapeHtml(t("ui.nav.my-reports"))}</h1>
    <div class="sv-toolbar">
      <select class="sv-select" aria-label="${escapeHtml(t("ui.field.status"))}" data-bind="my-reports-status">
        <option value=""${selected(state.myReports.status, "")}>${escapeHtml(t("ui.my-reports.filter.all-statuses"))}</option>
        ${(["open", "dismissed", "actioned"] as ReportStatus[])
          .map(
            (status) =>
              `<option value="${status}"${selected(state.myReports.status, status)}>${escapeHtml(t(`ui.report.status.${status}`))}</option>`,
          )
          .join("")}
      </select>
    </div>
    ${
      data.items.length === 0
        ? `<div class="sv-empty">${escapeHtml(t("ui.my-reports.empty"))}</div>`
        : `<div class="sv-table-wrap"><table class="sv-table">
            <thead><tr>
              <th scope="col">${escapeHtml(t("ui.field.created-at"))}</th>
              <th scope="col">${escapeHtml(t("ui.field.bookmark"))}</th>
              <th scope="col">${escapeHtml(t("ui.field.reason"))}</th>
              <th scope="col">${escapeHtml(t("ui.field.comment"))}</th>
              <th scope="col">${escapeHtml(t("ui.field.status"))}</th>
              <th scope="col"><span class="sv-visually-hidden">${escapeHtml(t("ui.field.actions"))}</span></th>
            </tr></thead>
            <tbody>${data.items
              .map(
                (report) =>
                  `<tr data-ctx="report:${escapeHtml(report.id)}">
                    <td><time datetime="${escapeHtml(report.createdAt)}">${escapeHtml(new Date(report.createdAt).toLocaleString(i18n.resolvedLanguage))}</time></td>
                    <td>${bookmarkCellHtml(report.bookmarkId, contexts)}</td>
                    <td><span class="sv-badge">${escapeHtml(t(`ui.report.reason.${report.reason}`))}</span></td>
                    <td>${escapeHtml(report.comment ?? "")}</td>
                    <td><span class="sv-badge${report.status === "actioned" ? " sv-badge--danger" : ""}">${escapeHtml(t(`ui.report.status.${report.status}`))}</span>${report.resolutionNote ? `<div class="sv-field-hint">${escapeHtml(report.resolutionNote)}</div>` : ""}</td>
                    <td class="sv-cell-actions">${
                      report.status === "open"
                        ? `<button type="button" class="sv-button sv-button--ghost sv-button--sm" data-action="open-report-edit" data-id="${escapeHtml(report.id)}">${escapeHtml(t("ui.action.edit"))}</button>
                           <button type="button" class="sv-button sv-button--ghost sv-button--sm" data-action="open-report-withdraw" data-id="${escapeHtml(report.id)}">${escapeHtml(t("ui.action.withdraw"))}</button>`
                        : ""
                    }</td>
                  </tr>`,
              )
              .join("")}</tbody>
          </table></div>`
    }
    ${paginationHtml(state.myReports.page, data.totalPages, "my-reports")}`,
    () => {
      state.myReports.items = data.items;
    },
  );
}

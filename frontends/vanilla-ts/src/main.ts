// The shared design is part of the contract — consumed verbatim from
// spec/design, never copied or overridden (see frontends/README.md).
import "../../../spec/design/tokens.css";
import "../../../spec/design/stackverse.css";

import {
  ApiError,
  apiGet,
  apiSend,
  fetchSession,
  fieldErrorFor,
  messageOf,
} from "./api";
import { localizeFieldError, RuntimeI18n } from "./i18n";
import type {
  AdminStats,
  AuditEntry,
  Bookmark,
  BookmarkCursorPage,
  BookmarkInput,
  Message,
  MessageInput,
  Page,
  Report,
  ReportInput,
  ReportReason,
  ReportStatus,
  Session,
  TagList,
  User,
  UserAccount,
  Visibility,
} from "./types";

const rootElement = document.getElementById("app");
if (!(rootElement instanceof HTMLElement)) throw new Error("#app not found");
const root = rootElement;

const i18n = new RuntimeI18n();
const SUPPORTED_LANGUAGES = ["en", "pl"] as const;
const THEME_OPTIONS = ["auto", "light", "dark"] as const;
const REPORTED_STORAGE_KEY = "stackverse.reported";
const THEME_STORAGE_KEY = "stackverse.theme";

type ToastVariant = "success" | "danger";

interface Toast {
  id: number;
  message: string;
  variant: ToastVariant;
}

interface BookmarkListState {
  q: string;
  tags: string[];
  pages: BookmarkCursorPage[];
  nextCursor?: string;
}

type FormValues = Record<string, string>;

type DialogState =
  | {
      kind: "bookmark-form";
      mode: "create" | "edit";
      bookmark?: Bookmark;
      values?: FormValues;
      error?: unknown;
    }
  | { kind: "delete-bookmark"; bookmark: Bookmark }
  | { kind: "report-bookmark"; bookmark: Bookmark; values?: FormValues; error?: unknown }
  | { kind: "edit-report"; report: Report; values?: FormValues; error?: unknown }
  | { kind: "withdraw-report"; report: Report }
  | { kind: "block-user"; user: UserAccount; values?: FormValues; error?: unknown }
  | {
      kind: "message-form";
      mode: "create" | "edit";
      message?: Message;
      values?: FormValues;
      error?: unknown;
    }
  | { kind: "delete-message"; message: Message };

const state = {
  session: null as Session | null,
  me: null as User | null,
  renderVersion: 0,
  dialog: null as DialogState | null,
  toasts: [] as Toast[],
  nextToastId: 0,
  bookmarks: { q: "", tags: [], pages: [] } as BookmarkListState,
  feed: { q: "", tags: [], pages: [] } as BookmarkListState,
  myReports: { status: "" as ReportStatus | "", page: 0, items: [] as Report[] },
  adminReports: { status: "open" as ReportStatus, page: 0, items: [] as Report[] },
  users: { q: "", page: 0, items: [] as UserAccount[] },
  audit: { actor: "", action: "", from: "", to: "", page: 0 },
  messages: { q: "", language: "", page: 0, items: [] as Message[] },
};

let pendingInputRender: number | undefined;

function t(key: string): string {
  return i18n.t(key);
}

function escapeHtml(value: unknown): string {
  return String(value ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function selected(value: string, expected: string): string {
  return value === expected ? " selected" : "";
}

function pathForApi(path: string, value: string): string {
  return `${path}/${encodeURIComponent(value)}`;
}

function isAdmin(user: User | null): boolean {
  return user?.roles.includes("admin") ?? false;
}

function isModerator(user: User | null): boolean {
  return user?.roles.includes("moderator") || user?.roles.includes("admin") || false;
}

function currentPath(): string {
  return window.location.pathname === "/" ? "/feed" : window.location.pathname;
}

function navClass(path: string, exact = false): string {
  const current = currentPath();
  const active = exact ? current === path : current === path || current.startsWith(`${path}/`);
  return `sv-nav-link${active ? " is-active" : ""}`;
}

function readStoredTheme(): "auto" | "light" | "dark" {
  try {
    const stored = localStorage.getItem(THEME_STORAGE_KEY);
    return stored === "light" || stored === "dark" ? stored : "auto";
  } catch {
    return "auto";
  }
}

function applyTheme(theme: "auto" | "light" | "dark"): void {
  if (theme === "auto") {
    document.documentElement.removeAttribute("data-theme");
    try {
      localStorage.removeItem(THEME_STORAGE_KEY);
    } catch {
      // Storage is optional.
    }
    return;
  }
  document.documentElement.setAttribute("data-theme", theme);
  try {
    localStorage.setItem(THEME_STORAGE_KEY, theme);
  } catch {
    // Storage is optional.
  }
}

function readReportedIds(): Set<string> {
  try {
    const raw = sessionStorage.getItem(REPORTED_STORAGE_KEY);
    return new Set(raw ? (JSON.parse(raw) as string[]) : []);
  } catch {
    return new Set();
  }
}

function writeReportedIds(ids: Set<string>): void {
  try {
    sessionStorage.setItem(REPORTED_STORAGE_KEY, JSON.stringify([...ids]));
  } catch {
    // Session memory is a convenience.
  }
}

function addReportedId(id: string): void {
  const ids = readReportedIds();
  ids.add(id);
  writeReportedIds(ids);
}

function removeReportedId(id: string): void {
  const ids = readReportedIds();
  ids.delete(id);
  writeReportedIds(ids);
}

function pushToast(message: string, variant: ToastVariant = "success"): void {
  const id = state.nextToastId;
  state.nextToastId += 1;
  state.toasts.push({ id, message, variant });
  window.setTimeout(() => {
    state.toasts = state.toasts.filter((toast) => toast.id !== id);
    void renderApp();
  }, 5000);
}

function toastHtml(): string {
  return `<div class="sv-toast-region" role="status" aria-live="polite">${state.toasts
    .map(
      (toast) =>
        `<div class="sv-toast sv-toast--${toast.variant}">${escapeHtml(toast.message)}</div>`,
    )
    .join("")}</div>`;
}

function loadingHtml(): string {
  return `<div class="sv-loading" role="status"><span class="sv-spinner"></span></div>`;
}

function errorHtml(error: unknown): string {
  if (error instanceof ApiError && error.status === 401) return loginPromptHtml();
  return `<div class="sv-alert sv-alert--danger" role="alert">${escapeHtml(messageOf(error))}</div>`;
}

function loginPromptHtml(): string {
  return `<div class="sv-empty"><a class="sv-button sv-button--primary" href="/auth/login">${escapeHtml(t("ui.action.login"))}</a></div>`;
}

function paginationHtml(page: number, totalPages: number, bind: string): string {
  if (totalPages <= 1) return "";
  return `<nav class="sv-pagination">
    <button type="button" class="sv-button sv-button--ghost sv-button--sm" aria-label="${escapeHtml(t("ui.action.previous"))}" data-action="page" data-bind="${bind}" data-page="${page - 1}"${page <= 0 ? " disabled" : ""}>&lsaquo;</button>
    <span>${page + 1} / ${totalPages}</span>
    <button type="button" class="sv-button sv-button--ghost sv-button--sm" aria-label="${escapeHtml(t("ui.action.next"))}" data-action="page" data-bind="${bind}" data-page="${page + 1}"${page >= totalPages - 1 ? " disabled" : ""}>&rsaquo;</button>
  </nav>`;
}

function fieldError(error: unknown, field: string): string | undefined {
  const entry = fieldErrorFor(error, field);
  return entry ? localizeFieldError(entry, t) : undefined;
}

function textFieldHtml({
  name,
  label,
  value,
  error,
  type = "text",
  hint,
}: {
  name: string;
  label: string;
  value: string;
  error?: string;
  type?: string;
  hint?: string;
}): string {
  const id = `field-${name}`;
  const errorId = `${id}-error`;
  const hintId = `${id}-hint`;
  const describedBy = [hint ? hintId : "", error ? errorId : ""].filter(Boolean).join(" ");
  return `<div class="sv-field${error ? " is-invalid" : ""}">
    <label class="sv-label" for="${id}">${escapeHtml(label)}</label>
    <input id="${id}" name="${escapeHtml(name)}" type="${escapeHtml(type)}" class="sv-input" value="${escapeHtml(value)}"${describedBy ? ` aria-describedby="${describedBy}"` : ""}${error ? " aria-invalid=\"true\"" : ""}>
    ${hint ? `<span class="sv-field-hint" id="${hintId}">${escapeHtml(hint)}</span>` : ""}
    ${error ? `<span class="sv-field-error" id="${errorId}">${escapeHtml(error)}</span>` : ""}
  </div>`;
}

function textareaFieldHtml({
  name,
  label,
  value,
  error,
}: {
  name: string;
  label: string;
  value: string;
  error?: string;
}): string {
  const id = `field-${name}`;
  const errorId = `${id}-error`;
  return `<div class="sv-field${error ? " is-invalid" : ""}">
    <label class="sv-label" for="${id}">${escapeHtml(label)}</label>
    <textarea id="${id}" name="${escapeHtml(name)}" class="sv-textarea"${error ? ` aria-describedby="${errorId}" aria-invalid="true"` : ""}>${escapeHtml(value)}</textarea>
    ${error ? `<span class="sv-field-error" id="${errorId}">${escapeHtml(error)}</span>` : ""}
  </div>`;
}

function selectFieldHtml({
  name,
  label,
  value,
  options,
  error,
}: {
  name: string;
  label: string;
  value: string;
  options: { value: string; label: string }[];
  error?: string;
}): string {
  const id = `field-${name}`;
  const errorId = `${id}-error`;
  return `<div class="sv-field${error ? " is-invalid" : ""}">
    <label class="sv-label" for="${id}">${escapeHtml(label)}</label>
    <select id="${id}" name="${escapeHtml(name)}" class="sv-select"${error ? ` aria-describedby="${errorId}" aria-invalid="true"` : ""}>
      ${options
        .map(
          (option) =>
            `<option value="${escapeHtml(option.value)}"${selected(value, option.value)}>${escapeHtml(option.label)}</option>`,
        )
        .join("")}
    </select>
    ${error ? `<span class="sv-field-error" id="${errorId}">${escapeHtml(error)}</span>` : ""}
  </div>`;
}

async function loadSessionAndMe(): Promise<void> {
  try {
    state.session = await fetchSession<Session>("/auth/session");
  } catch {
    state.session = { authenticated: false };
  }

  state.me = null;
  const session = state.session;
  if (session.authenticated) {
    try {
      state.me = await apiGet<User>("/api/v1/me");
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        state.session = { authenticated: false };
      }
    }
  }
}

function headerHtml(): string {
  const session = state.session;
  const authenticated = session?.authenticated === true;
  const theme = readStoredTheme();
  const adminLink = isModerator(state.me)
    ? `<a href="/admin" data-link class="${navClass("/admin")}">${escapeHtml(t("ui.nav.admin"))}</a>`
    : "";

  return `<header class="sv-header">
    <a href="/feed" data-link class="sv-brand">${escapeHtml(t("ui.app.title"))}</a>
    <nav class="sv-nav">
      ${
        authenticated
          ? `<a href="/bookmarks" data-link class="${navClass("/bookmarks", true)}">${escapeHtml(t("ui.nav.my-bookmarks"))}</a>
             <a href="/reports" data-link class="${navClass("/reports", true)}">${escapeHtml(t("ui.nav.my-reports"))}</a>`
          : ""
      }
      <a href="/feed" data-link class="${navClass("/feed", true)}">${escapeHtml(t("ui.nav.public-feed"))}</a>
      ${adminLink}
    </nav>
    <div class="sv-header-actions">
      <div class="sv-theme-switch" role="group" aria-label="${escapeHtml(t("ui.theme.label"))}">
        ${THEME_OPTIONS.map(
          (option) =>
            `<button type="button" class="sv-theme-option${theme === option ? " is-active" : ""}" data-action="theme" data-theme="${option}">${escapeHtml(t(`ui.theme.${option}`))}</button>`,
        ).join("")}
      </div>
      <div class="sv-lang-switch" role="group" aria-label="language">
        ${SUPPORTED_LANGUAGES.map(
          (lang) =>
            `<button type="button" lang="${lang}" class="sv-lang-option${i18n.lang === lang ? " is-active" : ""}" data-action="language" data-lang="${lang}">${lang.toUpperCase()}</button>`,
        ).join("")}
      </div>
      ${
        authenticated
          ? `<span class="sv-username">${escapeHtml(session.username)}</span>
             <button type="button" class="sv-button sv-button--ghost sv-button--sm" data-action="logout">${escapeHtml(t("ui.action.logout"))}</button>`
          : `<a class="sv-button sv-button--primary sv-button--sm" href="/auth/login">${escapeHtml(t("ui.action.login"))}</a>`
      }
    </div>
  </header>`;
}

function renderShell(mainHtml: string): void {
  root.innerHTML = `<div class="sv-app">
    ${headerHtml()}
    <main class="sv-main">${mainHtml}</main>
    ${dialogHtml()}
    ${toastHtml()}
  </div>`;

  const dialog = root.querySelector<HTMLDialogElement>("dialog.sv-dialog");
  if (dialog) {
    if (typeof dialog.showModal === "function" && !dialog.open) {
      dialog.showModal();
    } else {
      dialog.setAttribute("open", "");
    }
    dialog.addEventListener(
      "close",
      () => {
        state.dialog = null;
        void renderApp();
      },
      { once: true },
    );
  }
}

async function renderApp(): Promise<void> {
  if (window.location.pathname === "/") {
    history.replaceState(null, "", "/feed");
  }
  const version = ++state.renderVersion;
  renderShell(loadingHtml());
  let html = "";
  try {
    html = await routeHtml(currentPath());
  } catch (error) {
    html = errorHtml(error);
  }
  if (version !== state.renderVersion) return;
  renderShell(html);
}

async function routeHtml(path: string): Promise<string> {
  if (path === "/bookmarks") return myBookmarksPageHtml();
  if (path === "/reports") return myReportsPageHtml();
  if (path === "/feed") return publicFeedPageHtml();
  if (path === "/admin" || path.startsWith("/admin/")) return adminPageHtml(path);
  return publicFeedPageHtml();
}

function resetBookmarkList(list: BookmarkListState): void {
  list.pages = [];
  delete list.nextCursor;
}

async function fetchNextBookmarks(list: BookmarkListState, visibility?: Visibility): Promise<void> {
  const page = await apiGet<BookmarkCursorPage>("/api/v2/bookmarks", {
    ...(list.tags.length > 0 ? { tag: list.tags } : {}),
    ...(list.q ? { q: list.q } : {}),
    ...(visibility ? { visibility } : {}),
    ...(list.nextCursor ? { cursor: list.nextCursor } : {}),
  });
  list.pages.push(page);
  list.nextCursor = page.nextCursor;
}

async function ensureBookmarks(list: BookmarkListState, visibility?: Visibility): Promise<void> {
  if (list.pages.length === 0) await fetchNextBookmarks(list, visibility);
}

function allBookmarks(list: BookmarkListState): Bookmark[] {
  return list.pages.flatMap((page) => page.items);
}

function findBookmark(id: string): Bookmark | undefined {
  return [...allBookmarks(state.bookmarks), ...allBookmarks(state.feed)].find(
    (bookmark) => bookmark.id === id,
  );
}

function tagListHtml(
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
  const activeTags = list === "bookmarks" ? state.bookmarks.tags : state.feed.tags;
  const tags = bookmark.tags.length
    ? tagListHtml(bookmark.tags.map((tag) => ({ tag })), activeTags, list)
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
    .map((bookmark) => bookmarkCardHtml(bookmark, listName, renderActions(bookmark)))
    .join("")}</ul>
    ${
      list.nextCursor
        ? `<div class="sv-load-more"><button type="button" class="sv-button" data-action="load-more" data-list="${listName}">${escapeHtml(t("ui.action.load-more"))}</button></div>`
        : ""
    }`;
}

async function myBookmarksPageHtml(): Promise<string> {
  if (!state.session?.authenticated) {
    return `<section class="sv-content"><h1 class="sv-page-title">${escapeHtml(t("ui.nav.my-bookmarks"))}</h1>${loginPromptHtml()}</section>`;
  }

  const [tags] = await Promise.all([
    apiGet<TagList>("/api/v1/tags"),
    ensureBookmarks(state.bookmarks),
  ]);
  const filtered = state.bookmarks.q !== "" || state.bookmarks.tags.length > 0;
  return `<div class="sv-layout">
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
  </div>`;
}

async function publicFeedPageHtml(): Promise<string> {
  await ensureBookmarks(state.feed, "public");
  const reported = readReportedIds();
  const authenticated = state.session?.authenticated === true;
  const filtered = state.feed.q !== "" || state.feed.tags.length > 0;
  return `<section class="sv-content">
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
  </section>`;
}

async function bookmarkContextMap(reports: Report[]): Promise<Map<string, Bookmark | null>> {
  const entries = await Promise.all(
    reports.map(async (report) => {
      try {
        return [report.bookmarkId, await apiGet<Bookmark>(pathForApi("/api/v1/bookmarks", report.bookmarkId))] as const;
      } catch {
        return [report.bookmarkId, null] as const;
      }
    }),
  );
  return new Map(entries);
}

function bookmarkCellHtml(bookmarkId: string, contexts: Map<string, Bookmark | null>): string {
  const bookmark = contexts.get(bookmarkId);
  if (bookmark) {
    return `<strong>${escapeHtml(bookmark.title)}</strong>
      <div><a class="sv-bookmark-url" href="${escapeHtml(bookmark.url)}" target="_blank" rel="noreferrer">${escapeHtml(bookmark.url)}</a></div>`;
  }
  return `<span class="sv-cell-mono">${escapeHtml(bookmarkId)}</span>
    <div class="sv-field-hint">${escapeHtml(t("ui.reports.bookmark-unavailable"))}</div>`;
}

async function myReportsPageHtml(): Promise<string> {
  if (!state.session?.authenticated) {
    return `<section class="sv-content"><h1 class="sv-page-title">${escapeHtml(t("ui.nav.my-reports"))}</h1>${loginPromptHtml()}</section>`;
  }
  const data = await apiGet<Page<Report>>("/api/v1/reports", {
    ...(state.myReports.status ? { status: state.myReports.status } : {}),
    page: state.myReports.page,
  });
  state.myReports.items = data.items;
  const contexts = await bookmarkContextMap(data.items);
  return `<h1 class="sv-page-title">${escapeHtml(t("ui.nav.my-reports"))}</h1>
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
    ${paginationHtml(state.myReports.page, data.totalPages, "my-reports")}`;
}

async function adminPageHtml(path: string): Promise<string> {
  if (!state.session?.authenticated) return loginPromptHtml();
  if (!isModerator(state.me)) {
    return `<div class="sv-alert sv-alert--danger" role="alert">403</div>`;
  }

  const content = await adminContentHtml(path);
  return `<div class="sv-layout">
    <aside class="sv-sidebar">
      <h2 class="sv-sidebar-title">${escapeHtml(t("ui.nav.admin"))}</h2>
      <nav class="sv-nav sv-nav--vertical" aria-label="${escapeHtml(t("ui.nav.admin"))}">
        <a href="/admin" data-link class="${navClass("/admin", true)}">${escapeHtml(t("ui.admin.dashboard"))}</a>
        <a href="/admin/reports" data-link class="${navClass("/admin/reports", true)}">${escapeHtml(t("ui.admin.reports"))}</a>
        ${
          isAdmin(state.me)
            ? `<a href="/admin/users" data-link class="${navClass("/admin/users", true)}">${escapeHtml(t("ui.admin.users"))}</a>
               <a href="/admin/audit" data-link class="${navClass("/admin/audit", true)}">${escapeHtml(t("ui.admin.audit"))}</a>
               <a href="/admin/messages" data-link class="${navClass("/admin/messages", true)}">${escapeHtml(t("ui.admin.messages"))}</a>`
            : ""
        }
      </nav>
    </aside>
    <section class="sv-content">${content}</section>
  </div>`;
}

async function adminContentHtml(path: string): Promise<string> {
  if (path === "/admin/reports") return adminReportsPageHtml();
  if (path === "/admin/users") return usersPageHtml();
  if (path === "/admin/audit") return auditPageHtml();
  if (path === "/admin/messages") return messagesPageHtml();
  return dashboardPageHtml();
}

function dailyChartHtml(daily: AdminStats["daily"]): string {
  const width = 620;
  const height = 160;
  const left = 24;
  const bottom = 18;
  const top = 6;
  const chartHeight = height - bottom - top;
  const max = Math.max(1, ...daily.map((day) => Math.max(day.bookmarksCreated, day.activeUsers)));
  const slot = (width - left) / Math.max(1, daily.length);
  const barWidth = Math.max(2, slot / 2 - 1.5);
  const bars = daily
    .map((day, index) => {
      const x = left + index * slot;
      const created = (day.bookmarksCreated / max) * chartHeight;
      const active = (day.activeUsers / max) * chartHeight;
      return `<g>
        <title>${escapeHtml(`${day.date}: ${day.bookmarksCreated} ${t("ui.admin.stats.bookmarks-created")}, ${day.activeUsers} ${t("ui.admin.stats.active-users")}`)}</title>
        <rect class="sv-chart-bar" x="${x}" y="${top + chartHeight - created}" width="${barWidth}" height="${created}"></rect>
        <rect class="sv-chart-bar sv-chart-bar--secondary" x="${x + barWidth + 1}" y="${top + chartHeight - active}" width="${barWidth}" height="${active}"></rect>
      </g>`;
    })
    .join("");
  return `<svg class="sv-chart" viewBox="0 0 ${width} ${height}" role="img" aria-label="${escapeHtml(t("ui.admin.chart.label"))}">
    ${bars}
    <line class="sv-chart-axis" x1="${left}" y1="${top + chartHeight}" x2="${width}" y2="${top + chartHeight}"></line>
    <text class="sv-chart-label" x="0" y="${top + 10}">${max}</text>
    ${daily[0] ? `<text class="sv-chart-label" x="${left}" y="${height - 4}">${escapeHtml(daily[0].date)}</text>` : ""}
    ${daily.length > 1 ? `<text class="sv-chart-label" x="${width}" y="${height - 4}" text-anchor="end">${escapeHtml(daily[daily.length - 1]?.date ?? "")}</text>` : ""}
  </svg>`;
}

async function dashboardPageHtml(): Promise<string> {
  const stats = await apiGet<AdminStats>("/api/v1/admin/stats");
  const totals = [
    ["ui.admin.stats.users", stats.totals.users],
    ["ui.admin.stats.bookmarks", stats.totals.bookmarks],
    ["ui.admin.stats.public-bookmarks", stats.totals.publicBookmarks],
    ["ui.admin.stats.hidden-bookmarks", stats.totals.hiddenBookmarks],
    ["ui.admin.stats.open-reports", stats.totals.openReports],
  ] as const;
  return `<h1 class="sv-page-title">${escapeHtml(t("ui.admin.dashboard"))}</h1>
    <div class="sv-stats-grid">${totals
      .map(([key, value]) => {
        const body = `<span class="sv-stat-value">${value}</span><span class="sv-stat-label">${escapeHtml(key === "ui.admin.stats.open-reports" ? i18n.tCount(key, value) : t(key))}</span>`;
        return key === "ui.admin.stats.open-reports"
          ? `<a href="/admin/reports" data-link class="sv-stat sv-stat--link">${body}</a>`
          : `<div class="sv-stat">${body}</div>`;
      })
      .join("")}</div>
    <div class="sv-card">
      <div class="sv-legend">
        <span><span class="sv-legend-swatch"></span>${escapeHtml(t("ui.admin.stats.bookmarks-created"))}</span>
        <span><span class="sv-legend-swatch sv-legend-swatch--secondary"></span>${escapeHtml(t("ui.admin.stats.active-users"))}</span>
      </div>
      ${dailyChartHtml(stats.daily)}
    </div>
    ${
      stats.topTags.length
        ? `<div class="sv-card"><h2 class="sv-sidebar-title">${escapeHtml(t("ui.nav.tags"))}</h2>${tagListHtml(stats.topTags, [], "bookmarks", false)}</div>`
        : ""
    }`;
}

async function adminReportsPageHtml(): Promise<string> {
  const data = await apiGet<Page<Report>>("/api/v1/admin/reports", {
    status: state.adminReports.status,
    page: state.adminReports.page,
  });
  state.adminReports.items = data.items;
  const contexts = await bookmarkContextMap(data.items);
  return `<h1 class="sv-page-title">${escapeHtml(t("ui.admin.reports"))}</h1>
    <div class="sv-toolbar">
      <select class="sv-select" data-bind="admin-reports-status">
        ${(["open", "dismissed", "actioned"] as ReportStatus[])
          .map(
            (status) =>
              `<option value="${status}"${selected(state.adminReports.status, status)}>${escapeHtml(t(`ui.report.status.${status}`))}</option>`,
          )
          .join("")}
      </select>
    </div>
    ${
      data.items.length === 0
        ? `<div class="sv-empty">${escapeHtml(t("ui.reports.empty"))}</div>`
        : `<div class="sv-table-wrap"><table class="sv-table">
          <thead><tr>
            <th scope="col">${escapeHtml(t("ui.field.created-at"))}</th>
            <th scope="col">${escapeHtml(t("ui.field.bookmark"))}</th>
            <th scope="col">${escapeHtml(t("ui.field.reporter"))}</th>
            <th scope="col">${escapeHtml(t("ui.field.reason"))}</th>
            <th scope="col">${escapeHtml(t("ui.field.comment"))}</th>
            <th scope="col"><span class="sv-visually-hidden">${escapeHtml(t("ui.field.actions"))}</span></th>
          </tr></thead>
          <tbody>${data.items
            .map((report) => {
              const resolvedActions =
                report.status === "actioned"
                  ? `<button type="button" class="sv-button sv-button--ghost sv-button--sm" data-action="resolve-report" data-id="${escapeHtml(report.id)}" data-resolution="dismissed">${escapeHtml(t("ui.action.dismiss"))}</button>`
                  : `<button type="button" class="sv-button sv-button--ghost sv-button--sm" data-action="resolve-report" data-id="${escapeHtml(report.id)}" data-resolution="actioned">${escapeHtml(t("ui.action.action"))}</button>`;
              return `<tr data-ctx="report:${escapeHtml(report.id)}">
                <td><time datetime="${escapeHtml(report.createdAt)}">${escapeHtml(new Date(report.createdAt).toLocaleString(i18n.resolvedLanguage))}</time></td>
                <td>${bookmarkCellHtml(report.bookmarkId, contexts)}</td>
                <td>${escapeHtml(report.reporter)}</td>
                <td><span class="sv-badge">${escapeHtml(t(`ui.report.reason.${report.reason}`))}</span></td>
                <td>${escapeHtml(report.comment ?? "")}</td>
                <td class="sv-cell-actions">${
                  report.status === "open"
                    ? `<button type="button" class="sv-button sv-button--ghost sv-button--sm" data-action="resolve-report" data-id="${escapeHtml(report.id)}" data-resolution="dismissed">${escapeHtml(t("ui.action.dismiss"))}</button>
                       <button type="button" class="sv-button sv-button--ghost sv-button--sm" data-action="resolve-report" data-id="${escapeHtml(report.id)}" data-resolution="actioned">${escapeHtml(t("ui.action.action"))}</button>`
                    : `<span class="sv-badge${report.status === "actioned" ? " sv-badge--danger" : ""}">${escapeHtml(t(`ui.report.status.${report.status}`))}</span>
                       ${resolvedActions}
                       <button type="button" class="sv-button sv-button--ghost sv-button--sm" data-action="resolve-report" data-id="${escapeHtml(report.id)}" data-resolution="open">${escapeHtml(t("ui.action.reopen"))}</button>`
                }</td>
              </tr>`;
            })
            .join("")}</tbody>
        </table></div>`
    }
    ${paginationHtml(state.adminReports.page, data.totalPages, "admin-reports")}`;
}

async function usersPageHtml(): Promise<string> {
  const data = await apiGet<Page<UserAccount>>("/api/v1/admin/users", {
    ...(state.users.q ? { q: state.users.q } : {}),
    page: state.users.page,
  });
  state.users.items = data.items;
  return `<h1 class="sv-page-title">${escapeHtml(t("ui.admin.users"))}</h1>
    <div class="sv-toolbar">
      <input type="search" class="sv-input" role="searchbox" placeholder="${escapeHtml(t("ui.users.search.placeholder"))}" aria-label="${escapeHtml(t("ui.users.search.placeholder"))}" data-bind="users-q" value="${escapeHtml(state.users.q)}">
    </div>
    <div class="sv-table-wrap"><table class="sv-table">
      <thead><tr>
        <th scope="col">${escapeHtml(t("ui.field.username"))}</th>
        <th scope="col">${escapeHtml(t("ui.field.last-seen"))}</th>
        <th scope="col">${escapeHtml(t("ui.field.bookmarks"))}</th>
        <th scope="col">${escapeHtml(t("ui.field.status"))}</th>
        <th scope="col"><span class="sv-visually-hidden">${escapeHtml(t("ui.field.actions"))}</span></th>
      </tr></thead>
      <tbody>${data.items
        .map(
          (user) =>
            `<tr data-ctx="user:${escapeHtml(user.username)}">
              <td>${escapeHtml(user.username)}</td>
              <td><time datetime="${escapeHtml(user.lastSeen)}">${escapeHtml(new Date(user.lastSeen).toLocaleString(i18n.resolvedLanguage))}</time></td>
              <td>${user.bookmarkCount}</td>
              <td>${
                user.status === "blocked"
                  ? `<span class="sv-badge sv-badge--danger" title="${escapeHtml(user.blockedReason ?? "")}">${escapeHtml(t("ui.user.status.blocked"))}</span>`
                  : `<span class="sv-badge sv-badge--success">${escapeHtml(t("ui.user.status.active"))}</span>`
              }</td>
              <td class="sv-cell-actions">${
                user.status === "blocked"
                  ? `<button type="button" class="sv-button sv-button--sm" data-action="unblock-user" data-username="${escapeHtml(user.username)}">${escapeHtml(t("ui.action.unblock"))}</button>`
                  : state.me?.username !== user.username
                    ? `<button type="button" class="sv-button sv-button--ghost sv-button--sm" data-action="open-block-user" data-username="${escapeHtml(user.username)}">${escapeHtml(t("ui.action.block"))}</button>`
                    : ""
              }</td>
            </tr>`,
        )
        .join("")}</tbody>
    </table></div>
    ${paginationHtml(state.users.page, data.totalPages, "users")}`;
}

function endOfDayIso(day: string): string {
  return new Date(`${day}T23:59:59.999`).toISOString().replace(".999Z", ".999999Z");
}

async function auditPageHtml(): Promise<string> {
  const data = await apiGet<Page<AuditEntry>>("/api/v1/admin/audit-log", {
    ...(state.audit.actor ? { actor: state.audit.actor } : {}),
    ...(state.audit.action ? { action: state.audit.action } : {}),
    ...(state.audit.from ? { from: new Date(`${state.audit.from}T00:00:00`).toISOString() } : {}),
    ...(state.audit.to ? { to: endOfDayIso(state.audit.to) } : {}),
    page: state.audit.page,
  });
  const knownActions = [
    "message.created",
    "message.updated",
    "message.deleted",
    "report.resolved",
    "bookmark.status-changed",
    "user.blocked",
    "user.unblocked",
  ];
  return `<h1 class="sv-page-title">${escapeHtml(t("ui.admin.audit"))}</h1>
    <div class="sv-toolbar">
      <input class="sv-input" placeholder="${escapeHtml(t("ui.field.actor"))}" data-bind="audit-actor" value="${escapeHtml(state.audit.actor)}">
      <input class="sv-input" placeholder="${escapeHtml(t("ui.audit.action.placeholder"))}" aria-label="${escapeHtml(t("ui.audit.action.placeholder"))}" list="audit-known-actions" data-bind="audit-action" value="${escapeHtml(state.audit.action)}">
      <datalist id="audit-known-actions">${knownActions.map((action) => `<option value="${escapeHtml(action)}"></option>`).join("")}</datalist>
      <label class="sv-toolbar-field"><span class="sv-label">${escapeHtml(t("ui.field.from"))}</span><input type="date" class="sv-input" data-bind="audit-from" value="${escapeHtml(state.audit.from)}"></label>
      <label class="sv-toolbar-field"><span class="sv-label">${escapeHtml(t("ui.field.to"))}</span><input type="date" class="sv-input" data-bind="audit-to" value="${escapeHtml(state.audit.to)}"></label>
      <button type="button" class="sv-button sv-button--ghost" data-action="clear-audit">${escapeHtml(t("ui.action.clear-filters"))}</button>
    </div>
    <div class="sv-table-wrap"><table class="sv-table">
      <thead><tr>
        <th scope="col">${escapeHtml(t("ui.field.created-at"))}</th>
        <th scope="col">${escapeHtml(t("ui.field.actor"))}</th>
        <th scope="col">${escapeHtml(t("ui.field.action"))}</th>
        <th scope="col">${escapeHtml(t("ui.field.target"))}</th>
      </tr></thead>
      <tbody>${data.items
        .map(
          (entry) =>
            `<tr>
              <td><time datetime="${escapeHtml(entry.createdAt)}">${escapeHtml(new Date(entry.createdAt).toLocaleString(i18n.resolvedLanguage))}</time></td>
              <td>${escapeHtml(entry.actor)}</td>
              <td><span class="sv-badge">${escapeHtml(entry.action)}</span></td>
              <td><span class="sv-cell-mono">${escapeHtml(`${entry.targetType}:${entry.targetId}`)}</span></td>
            </tr>`,
        )
        .join("")}</tbody>
    </table></div>
    ${paginationHtml(state.audit.page, data.totalPages, "audit")}`;
}

async function messagesPageHtml(): Promise<string> {
  const data = await apiGet<Page<Message>>("/api/v1/messages", {
    ...(state.messages.q ? { q: state.messages.q } : {}),
    ...(state.messages.language ? { language: state.messages.language } : {}),
    page: state.messages.page,
  });
  state.messages.items = data.items;
  return `<h1 class="sv-page-title">${escapeHtml(t("ui.admin.messages"))}</h1>
    <div class="sv-toolbar">
      <input class="sv-input" placeholder="${escapeHtml(t("ui.messages.search.placeholder"))}" aria-label="${escapeHtml(t("ui.messages.search.placeholder"))}" data-bind="messages-q" value="${escapeHtml(state.messages.q)}">
      <select class="sv-select" aria-label="${escapeHtml(t("ui.field.language"))}" data-bind="messages-language">
        <option value=""${selected(state.messages.language, "")}>${escapeHtml(t("ui.messages.filter.all-languages"))}</option>
        ${SUPPORTED_LANGUAGES.map((lang) => `<option value="${lang}"${selected(state.messages.language, lang)}>${lang}</option>`).join("")}
      </select>
      <button type="button" class="sv-button sv-button--ghost" data-action="clear-messages">${escapeHtml(t("ui.action.clear-filters"))}</button>
      <button type="button" class="sv-button sv-button--primary" data-action="open-message-create">${escapeHtml(t("ui.action.add"))}</button>
    </div>
    ${
      data.items.length === 0
        ? `<div class="sv-empty">${escapeHtml(t("ui.messages.empty"))}</div>`
        : `<div class="sv-table-wrap"><table class="sv-table">
          <thead><tr>
            <th scope="col">${escapeHtml(t("ui.field.key"))}</th>
            <th scope="col">${escapeHtml(t("ui.field.language"))}</th>
            <th scope="col">${escapeHtml(t("ui.field.text"))}</th>
            <th scope="col"><span class="sv-visually-hidden">${escapeHtml(t("ui.field.actions"))}</span></th>
          </tr></thead>
          <tbody>${data.items
            .map(
              (message) =>
                `<tr data-ctx="message:${escapeHtml(message.id)}">
                  <td class="sv-cell-mono">${escapeHtml(message.key)}</td>
                  <td><span class="sv-badge">${escapeHtml(message.language)}</span></td>
                  <td>${escapeHtml(message.text)}</td>
                  <td class="sv-cell-actions">
                    <button type="button" class="sv-button sv-button--ghost sv-button--sm" data-action="open-message-edit" data-id="${escapeHtml(message.id)}">${escapeHtml(t("ui.action.edit"))}</button>
                    <button type="button" class="sv-button sv-button--ghost sv-button--sm" data-action="open-message-delete" data-id="${escapeHtml(message.id)}">${escapeHtml(t("ui.action.delete"))}</button>
                  </td>
                </tr>`,
            )
            .join("")}</tbody>
        </table></div>`
    }
    ${paginationHtml(state.messages.page, data.totalPages, "messages")}`;
}

function dialogHtml(): string {
  if (!state.dialog) return "";
  switch (state.dialog.kind) {
    case "bookmark-form":
      return bookmarkFormDialogHtml(state.dialog);
    case "delete-bookmark":
      return confirmDialogHtml({
        title: t("ui.action.delete"),
        body: t("ui.confirm.delete-bookmark"),
        confirm: t("ui.action.delete"),
        action: "confirm-bookmark-delete",
        ctx: `bookmark:${state.dialog.bookmark.id}`,
      });
    case "report-bookmark":
      return reportBookmarkDialogHtml(state.dialog);
    case "edit-report":
      return editReportDialogHtml(state.dialog);
    case "withdraw-report":
      return confirmDialogHtml({
        title: t("ui.action.withdraw"),
        body: t("ui.confirm.withdraw-report"),
        confirm: t("ui.action.withdraw"),
        action: "confirm-report-withdraw",
        ctx: `report:${state.dialog.report.id}`,
      });
    case "block-user":
      return blockUserDialogHtml(state.dialog);
    case "message-form":
      return messageFormDialogHtml(state.dialog);
    case "delete-message":
      return confirmDialogHtml({
        title: t("ui.action.delete"),
        body: t("ui.confirm.delete-message"),
        confirm: t("ui.action.delete"),
        action: "confirm-message-delete",
        ctx: `message:${state.dialog.message.id}`,
      });
  }
}

function dialogShell(title: string, body: string, ctx?: string): string {
  return `<dialog class="sv-dialog"${ctx ? ` data-ctx="${escapeHtml(ctx)}"` : ""}>
    <h2 class="sv-dialog-title">${escapeHtml(title)}</h2>
    ${body}
  </dialog>`;
}

function confirmDialogHtml({
  title,
  body,
  confirm,
  action,
  ctx,
}: {
  title: string;
  body: string;
  confirm: string;
  action: string;
  ctx: string;
}): string {
  return dialogShell(
    title,
    `<p>${escapeHtml(body)}</p>
    <div class="sv-form-actions">
      <button type="button" class="sv-button" data-action="close-dialog">${escapeHtml(t("ui.action.cancel"))}</button>
      <button type="button" class="sv-button sv-button--danger" data-action="${action}">${escapeHtml(confirm)}</button>
    </div>`,
    ctx,
  );
}

function bookmarkFormDialogHtml(dialog: Extract<DialogState, { kind: "bookmark-form" }>): string {
  const bookmark = dialog.bookmark;
  const values = dialog.values ?? {
    url: bookmark?.url ?? "",
    title: bookmark?.title ?? "",
    notes: bookmark?.notes ?? "",
    tags: bookmark?.tags.join(" ") ?? "",
    visibility: bookmark?.visibility ?? "private",
  };
  const error = dialog.error;
  return dialogShell(
    t(dialog.mode === "edit" ? "ui.bookmarks.dialog.edit" : "ui.bookmarks.dialog.add"),
    `<form class="sv-form" data-form="bookmark">
      ${textFieldHtml({ name: "url", label: t("ui.field.url"), value: values.url ?? "", error: fieldError(error, "url"), type: "url" })}
      ${textFieldHtml({ name: "title", label: t("ui.field.title"), value: values.title ?? "", error: fieldError(error, "title") })}
      ${textareaFieldHtml({ name: "notes", label: t("ui.field.notes"), value: values.notes ?? "", error: fieldError(error, "notes") })}
      ${textFieldHtml({ name: "tags", label: t("ui.field.tags"), value: values.tags ?? "", error: fieldError(error, "tags"), hint: t("ui.field.tags.hint") })}
      ${selectFieldHtml({
        name: "visibility",
        label: t("ui.field.visibility"),
        value: values.visibility ?? "private",
        error: fieldError(error, "visibility"),
        options: [
          { value: "private", label: t("ui.visibility.private") },
          { value: "public", label: t("ui.visibility.public") },
        ],
      })}
      ${error instanceof ApiError && error.status === 409 ? `<div class="sv-alert sv-alert--warning" role="alert">${escapeHtml(t("error.bookmark.hidden-publish"))}</div>` : ""}
      <div class="sv-form-actions">
        <button type="button" class="sv-button" data-action="close-dialog">${escapeHtml(t("ui.action.cancel"))}</button>
        <button type="submit" class="sv-button sv-button--primary">${escapeHtml(t("ui.action.save"))}</button>
      </div>
    </form>`,
    bookmark ? `bookmark:${bookmark.id}` : undefined,
  );
}

const reportReasons: ReportReason[] = ["spam", "offensive", "broken-link", "other"];

function reportBookmarkDialogHtml(dialog: Extract<DialogState, { kind: "report-bookmark" }>): string {
  const values = dialog.values ?? { reason: "spam", comment: "" };
  const error = dialog.error;
  return dialogShell(
    `${t("ui.action.report")} - ${dialog.bookmark.title}`,
    `<form class="sv-form" data-form="report-bookmark">
      ${selectFieldHtml({
        name: "reason",
        label: t("ui.field.reason"),
        value: values.reason ?? "spam",
        error: fieldError(error, "reason"),
        options: reportReasons.map((reason) => ({ value: reason, label: t(`ui.report.reason.${reason}`) })),
      })}
      ${textareaFieldHtml({ name: "comment", label: t("ui.field.comment"), value: values.comment ?? "", error: fieldError(error, "comment") })}
      <div class="sv-form-actions">
        <button type="button" class="sv-button" data-action="close-dialog">${escapeHtml(t("ui.action.cancel"))}</button>
        <button type="submit" class="sv-button sv-button--primary">${escapeHtml(t("ui.action.report"))}</button>
      </div>
    </form>`,
    `bookmark:${dialog.bookmark.id}`,
  );
}

function editReportDialogHtml(dialog: Extract<DialogState, { kind: "edit-report" }>): string {
  const values = dialog.values ?? {
    reason: dialog.report.reason,
    comment: dialog.report.comment ?? "",
  };
  const error = dialog.error;
  return dialogShell(
    t("ui.my-reports.dialog.edit"),
    `<form class="sv-form" data-form="edit-report">
      ${selectFieldHtml({
        name: "reason",
        label: t("ui.field.reason"),
        value: values.reason ?? "spam",
        error: fieldError(error, "reason"),
        options: reportReasons.map((reason) => ({ value: reason, label: t(`ui.report.reason.${reason}`) })),
      })}
      ${textareaFieldHtml({ name: "comment", label: t("ui.field.comment"), value: values.comment ?? "", error: fieldError(error, "comment") })}
      ${error instanceof ApiError && error.status === 409 ? `<div class="sv-alert sv-alert--warning" role="alert">${escapeHtml(error.message)}</div>` : ""}
      <div class="sv-form-actions">
        <button type="button" class="sv-button" data-action="close-dialog">${escapeHtml(t("ui.action.cancel"))}</button>
        <button type="submit" class="sv-button sv-button--primary">${escapeHtml(t("ui.action.save"))}</button>
      </div>
    </form>`,
    `report:${dialog.report.id}`,
  );
}

function blockUserDialogHtml(dialog: Extract<DialogState, { kind: "block-user" }>): string {
  const values = dialog.values ?? { reason: "" };
  const error = dialog.error;
  return dialogShell(
    `${t("ui.action.block")} - ${dialog.user.username}`,
    `<form class="sv-form" data-form="block-user">
      ${textareaFieldHtml({ name: "reason", label: t("ui.field.reason"), value: values.reason ?? "", error: fieldError(error, "reason") })}
      ${error instanceof ApiError && error.status === 409 ? `<div class="sv-alert sv-alert--warning" role="alert">${escapeHtml(error.message)}</div>` : ""}
      <div class="sv-form-actions">
        <button type="button" class="sv-button" data-action="close-dialog">${escapeHtml(t("ui.action.cancel"))}</button>
        <button type="submit" class="sv-button sv-button--danger">${escapeHtml(t("ui.action.block"))}</button>
      </div>
    </form>`,
    `user:${dialog.user.username}`,
  );
}

function messageFormDialogHtml(dialog: Extract<DialogState, { kind: "message-form" }>): string {
  const message = dialog.message;
  const values = dialog.values ?? {
    key: message?.key ?? "",
    language: message?.language ?? "en",
    text: message?.text ?? "",
    description: message?.description ?? "",
  };
  const languageOptions = [
    ...(SUPPORTED_LANGUAGES.includes(values.language as (typeof SUPPORTED_LANGUAGES)[number])
      ? []
      : [{ value: values.language, label: values.language }]),
    ...SUPPORTED_LANGUAGES.map((lang) => ({ value: lang, label: lang })),
  ];
  const error = dialog.error;
  return dialogShell(
    t(dialog.mode === "edit" ? "ui.messages.dialog.edit" : "ui.messages.dialog.add"),
    `<form class="sv-form" data-form="message">
      ${textFieldHtml({ name: "key", label: t("ui.field.key"), value: values.key ?? "", error: fieldError(error, "key") })}
      ${selectFieldHtml({ name: "language", label: t("ui.field.language"), value: values.language ?? "en", error: fieldError(error, "language"), options: languageOptions })}
      ${textareaFieldHtml({ name: "text", label: t("ui.field.text"), value: values.text ?? "", error: fieldError(error, "text") })}
      ${textareaFieldHtml({ name: "description", label: t("ui.field.description"), value: values.description ?? "", error: fieldError(error, "description") })}
      ${error instanceof ApiError && error.status === 409 ? `<div class="sv-alert sv-alert--warning" role="alert">${escapeHtml(error.message)}</div>` : ""}
      <div class="sv-form-actions">
        <button type="button" class="sv-button" data-action="close-dialog">${escapeHtml(t("ui.action.cancel"))}</button>
        <button type="submit" class="sv-button sv-button--primary">${escapeHtml(t("ui.action.save"))}</button>
      </div>
    </form>`,
    message ? `message:${message.id}` : undefined,
  );
}

function navigate(path: string): void {
  if (path === currentPath()) return;
  history.pushState(null, "", path);
  void renderApp();
}

function scheduleRender(): void {
  if (pendingInputRender !== undefined) window.clearTimeout(pendingInputRender);
  pendingInputRender = window.setTimeout(() => {
    pendingInputRender = undefined;
    void renderApp();
  }, 250);
}

function updateBoundValue(bind: string, value: string, immediate: boolean): void {
  switch (bind) {
    case "bookmarks-q":
      state.bookmarks.q = value;
      resetBookmarkList(state.bookmarks);
      break;
    case "feed-q":
      state.feed.q = value;
      resetBookmarkList(state.feed);
      break;
    case "my-reports-status":
      state.myReports.status = value as ReportStatus | "";
      state.myReports.page = 0;
      break;
    case "admin-reports-status":
      state.adminReports.status = value as ReportStatus;
      state.adminReports.page = 0;
      break;
    case "users-q":
      state.users.q = value;
      state.users.page = 0;
      break;
    case "audit-actor":
      state.audit.actor = value;
      state.audit.page = 0;
      break;
    case "audit-action":
      state.audit.action = value;
      state.audit.page = 0;
      break;
    case "audit-from":
      state.audit.from = value;
      state.audit.page = 0;
      break;
    case "audit-to":
      state.audit.to = value;
      state.audit.page = 0;
      break;
    case "messages-q":
      state.messages.q = value;
      state.messages.page = 0;
      break;
    case "messages-language":
      state.messages.language = value;
      state.messages.page = 0;
      break;
  }
  if (immediate) void renderApp();
  else scheduleRender();
}

function formValues(form: HTMLFormElement): FormValues {
  const data = new FormData(form);
  const values: FormValues = {};
  for (const [key, value] of data.entries()) values[key] = String(value);
  return values;
}

function reportBody(values: FormValues): ReportInput {
  return {
    reason: (values.reason || "spam") as ReportReason,
    ...(values.comment ? { comment: values.comment } : {}),
  };
}

function bookmarkBody(values: FormValues): BookmarkInput {
  return {
    url: values.url ?? "",
    title: values.title ?? "",
    ...(values.notes ? { notes: values.notes } : {}),
    tags: (values.tags ?? "").split(/[\s,]+/).filter(Boolean),
    visibility: (values.visibility || "private") as Visibility,
  };
}

function messageBody(values: FormValues): MessageInput {
  return {
    key: values.key ?? "",
    language: values.language ?? "en",
    text: values.text ?? "",
    ...(values.description ? { description: values.description } : {}),
  };
}

async function handleForm(form: HTMLFormElement): Promise<void> {
  if (!state.dialog) return;
  const values = formValues(form);
  try {
    switch (form.dataset.form) {
      case "bookmark": {
        if (state.dialog.kind !== "bookmark-form") return;
        if (state.dialog.mode === "edit" && state.dialog.bookmark) {
          await apiSend<Bookmark>(
            "PUT",
            pathForApi("/api/v1/bookmarks", state.dialog.bookmark.id),
            bookmarkBody(values),
          );
        } else {
          await apiSend<Bookmark>("POST", "/api/v1/bookmarks", bookmarkBody(values));
        }
        resetBookmarkList(state.bookmarks);
        state.dialog = null;
        break;
      }
      case "report-bookmark": {
        if (state.dialog.kind !== "report-bookmark") return;
        try {
          await apiSend<Report>(
            "POST",
            `${pathForApi("/api/v1/bookmarks", state.dialog.bookmark.id)}/reports`,
            reportBody(values),
          );
          pushToast(t("ui.toast.report-submitted"));
        } catch (error) {
          if (error instanceof ApiError && error.status === 409) {
            pushToast(t("ui.toast.report-duplicate"));
          } else {
            throw error;
          }
        }
        addReportedId(state.dialog.bookmark.id);
        state.dialog = null;
        break;
      }
      case "edit-report": {
        if (state.dialog.kind !== "edit-report") return;
        await apiSend<Report>(
          "PUT",
          pathForApi("/api/v1/reports", state.dialog.report.id),
          reportBody(values),
        );
        pushToast(t("ui.toast.report-updated"));
        state.dialog = null;
        break;
      }
      case "block-user": {
        if (state.dialog.kind !== "block-user") return;
        await apiSend<UserAccount>(
          "PUT",
          `${pathForApi("/api/v1/admin/users", state.dialog.user.username)}/status`,
          { status: "blocked", reason: values.reason ?? "" },
        );
        state.dialog = null;
        break;
      }
      case "message": {
        if (state.dialog.kind !== "message-form") return;
        if (state.dialog.mode === "edit" && state.dialog.message) {
          await apiSend<Message>(
            "PUT",
            pathForApi("/api/v1/messages", state.dialog.message.id),
            messageBody(values),
          );
          pushToast(t("ui.toast.message-updated"));
        } else {
          await apiSend<Message>("POST", "/api/v1/messages", messageBody(values));
          pushToast(t("ui.toast.message-created"));
        }
        await i18n.load();
        state.dialog = null;
        break;
      }
    }
  } catch (error) {
    state.dialog = { ...state.dialog, values, error } as DialogState;
  }
  await renderApp();
}

async function handleAction(element: HTMLElement): Promise<void> {
  const action = element.dataset.action;
  if (!action || element.hasAttribute("disabled")) return;

  switch (action) {
    case "theme":
      applyTheme((element.dataset.theme ?? "auto") as "auto" | "light" | "dark");
      await renderApp();
      break;
    case "language":
      await i18n.setLanguage(element.dataset.lang ?? "en");
      await renderApp();
      break;
    case "logout":
      await fetch("/auth/logout", { method: "POST", credentials: "include" });
      state.session = { authenticated: false };
      state.me = null;
      state.dialog = null;
      navigate("/feed");
      break;
    case "close-dialog":
      state.dialog = null;
      await renderApp();
      break;
    case "toggle-tag": {
      const list = element.dataset.list === "feed" ? state.feed : state.bookmarks;
      const tag = element.dataset.tag ?? "";
      list.tags = list.tags.includes(tag)
        ? list.tags.filter((existing) => existing !== tag)
        : [...list.tags, tag];
      resetBookmarkList(list);
      await renderApp();
      break;
    }
    case "load-more": {
      if (element.dataset.list === "feed") await fetchNextBookmarks(state.feed, "public");
      else await fetchNextBookmarks(state.bookmarks);
      await renderApp();
      break;
    }
    case "page": {
      const page = Math.max(0, Number(element.dataset.page ?? 0));
      switch (element.dataset.bind) {
        case "my-reports":
          state.myReports.page = page;
          break;
        case "admin-reports":
          state.adminReports.page = page;
          break;
        case "users":
          state.users.page = page;
          break;
        case "audit":
          state.audit.page = page;
          break;
        case "messages":
          state.messages.page = page;
          break;
      }
      await renderApp();
      break;
    }
    case "open-bookmark-create":
      state.dialog = { kind: "bookmark-form", mode: "create" };
      await renderApp();
      break;
    case "open-bookmark-edit": {
      const bookmark = findBookmark(element.dataset.id ?? "");
      if (bookmark) state.dialog = { kind: "bookmark-form", mode: "edit", bookmark };
      await renderApp();
      break;
    }
    case "open-bookmark-delete": {
      const bookmark = findBookmark(element.dataset.id ?? "");
      if (bookmark) state.dialog = { kind: "delete-bookmark", bookmark };
      await renderApp();
      break;
    }
    case "confirm-bookmark-delete":
      if (state.dialog?.kind === "delete-bookmark") {
        await apiSend<void>("DELETE", pathForApi("/api/v1/bookmarks", state.dialog.bookmark.id));
        pushToast(t("ui.toast.bookmark-deleted"));
        resetBookmarkList(state.bookmarks);
        state.dialog = null;
        await renderApp();
      }
      break;
    case "open-report": {
      const bookmark = findBookmark(element.dataset.id ?? "");
      if (bookmark) state.dialog = { kind: "report-bookmark", bookmark };
      await renderApp();
      break;
    }
    case "open-report-edit": {
      const report = state.myReports.items.find((item) => item.id === element.dataset.id);
      if (report) state.dialog = { kind: "edit-report", report };
      await renderApp();
      break;
    }
    case "open-report-withdraw": {
      const report = state.myReports.items.find((item) => item.id === element.dataset.id);
      if (report) state.dialog = { kind: "withdraw-report", report };
      await renderApp();
      break;
    }
    case "confirm-report-withdraw":
      if (state.dialog?.kind === "withdraw-report") {
        await apiSend<void>("DELETE", pathForApi("/api/v1/reports", state.dialog.report.id));
        removeReportedId(state.dialog.report.bookmarkId);
        pushToast(t("ui.toast.report-withdrawn"));
        state.dialog = null;
        await renderApp();
      }
      break;
    case "resolve-report":
      await apiSend<Report>("PUT", pathForApi("/api/v1/admin/reports", element.dataset.id ?? ""), {
        resolution: element.dataset.resolution ?? "dismissed",
      });
      await renderApp();
      break;
    case "open-block-user": {
      const user = state.users.items.find((item) => item.username === element.dataset.username);
      if (user) state.dialog = { kind: "block-user", user };
      await renderApp();
      break;
    }
    case "unblock-user":
      await apiSend<UserAccount>(
        "PUT",
        `${pathForApi("/api/v1/admin/users", element.dataset.username ?? "")}/status`,
        { status: "active" },
      );
      await renderApp();
      break;
    case "clear-audit":
      state.audit.actor = "";
      state.audit.action = "";
      state.audit.from = "";
      state.audit.to = "";
      state.audit.page = 0;
      await renderApp();
      break;
    case "open-message-create":
      state.dialog = { kind: "message-form", mode: "create" };
      await renderApp();
      break;
    case "open-message-edit": {
      const message = state.messages.items.find((item) => item.id === element.dataset.id);
      if (message) state.dialog = { kind: "message-form", mode: "edit", message };
      await renderApp();
      break;
    }
    case "open-message-delete": {
      const message = state.messages.items.find((item) => item.id === element.dataset.id);
      if (message) state.dialog = { kind: "delete-message", message };
      await renderApp();
      break;
    }
    case "confirm-message-delete":
      if (state.dialog?.kind === "delete-message") {
        await apiSend<void>("DELETE", pathForApi("/api/v1/messages", state.dialog.message.id));
        pushToast(t("ui.toast.message-deleted"));
        await i18n.load();
        state.dialog = null;
        await renderApp();
      }
      break;
    case "clear-messages":
      state.messages.q = "";
      state.messages.language = "";
      state.messages.page = 0;
      await renderApp();
      break;
  }
}

document.addEventListener("click", (event) => {
  const target = event.target instanceof Element ? event.target : null;
  if (!target) return;

  const link = target.closest<HTMLAnchorElement>("a[data-link]");
  if (link) {
    event.preventDefault();
    navigate(new URL(link.href).pathname);
    return;
  }

  const action = target.closest<HTMLElement>("[data-action]");
  if (action) {
    event.preventDefault();
    void handleAction(action);
  }
});

document.addEventListener("input", (event) => {
  const input = event.target as HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement;
  const bind = input.dataset.bind;
  if (!bind) return;
  const immediate = input.tagName === "SELECT" || input.getAttribute("type") === "date";
  updateBoundValue(bind, input.value, immediate);
});

document.addEventListener("change", (event) => {
  const input = event.target as HTMLInputElement | HTMLSelectElement;
  const bind = input.dataset.bind;
  if (!bind) return;
  if (input.tagName === "SELECT" || input.getAttribute("type") === "date") {
    updateBoundValue(bind, input.value, true);
  }
});

document.addEventListener("submit", (event) => {
  const form = event.target instanceof HTMLFormElement ? event.target : null;
  if (!form) return;
  event.preventDefault();
  void handleForm(form);
});

window.addEventListener("popstate", () => {
  void renderApp();
});

async function bootstrap(): Promise<void> {
  if (import.meta.env.DEV) {
    const [{ forwardConsoleToDevServer }, { installUserActionLog }] = await Promise.all([
      import("./dev/forwardConsoleToDevServer"),
      import("./dev/logUserActions"),
    ]);
    forwardConsoleToDevServer();
    installUserActionLog();
  }
  await i18n.load();
  await loadSessionAndMe();
  await renderApp();
}

void bootstrap();

import { tick } from "svelte";
import { vi } from "vitest";
import { i18n } from "../lib/i18n";
import { me, session } from "../lib/session";
import type { Bookmark, Page, Report, User } from "../lib/types";

export const timestamp = "2026-07-11T12:00:00.000Z";

export const defaultMessages: Record<string, string> = {
  "error.bookmark.hidden-publish": "A hidden bookmark cannot be public",
  "ui.action.action": "Action",
  "ui.action.add": "Add bookmark",
  "ui.action.block": "Block",
  "ui.action.cancel": "Cancel",
  "ui.action.clear-filters": "Clear filters",
  "ui.action.delete": "Delete",
  "ui.action.dismiss": "Dismiss",
  "ui.action.edit": "Edit",
  "ui.action.load-more": "Load more",
  "ui.action.login": "Log in",
  "ui.action.logout": "Log out",
  "ui.action.next": "Next",
  "ui.action.previous": "Previous",
  "ui.action.reopen": "Reopen",
  "ui.action.report": "Report",
  "ui.action.save": "Save",
  "ui.action.unblock": "Unblock",
  "ui.action.withdraw": "Withdraw",
  "ui.admin.audit": "Audit",
  "ui.admin.dashboard": "Dashboard",
  "ui.admin.messages": "Messages",
  "ui.admin.reports": "Reports",
  "ui.admin.users": "Users",
  "ui.app.title": "Stackverse",
  "ui.bookmark.hidden": "Hidden",
  "ui.bookmarks.dialog.add": "Add bookmark",
  "ui.bookmarks.dialog.edit": "Edit bookmark",
  "ui.bookmarks.empty": "No bookmarks yet",
  "ui.bookmarks.no-matches": "No matching bookmarks",
  "ui.bookmarks.search.placeholder": "Search bookmarks",
  "ui.confirm.delete-bookmark": "Delete this bookmark?",
  "ui.confirm.delete-message": "Delete this message?",
  "ui.confirm.withdraw-report": "Withdraw this report?",
  "ui.field.actions": "Actions",
  "ui.field.actor": "Actor",
  "ui.field.bookmark": "Bookmark",
  "ui.field.bookmarks": "Bookmarks",
  "ui.field.comment": "Comment",
  "ui.field.created-at": "Created at",
  "ui.field.description": "Description",
  "ui.field.from": "From",
  "ui.field.key": "Key",
  "ui.field.language": "Language",
  "ui.field.last-seen": "Last seen",
  "ui.field.notes": "Notes",
  "ui.field.reason": "Reason",
  "ui.field.reporter": "Reporter",
  "ui.field.status": "Status",
  "ui.field.tags": "Tags",
  "ui.field.tags.hint": "Separate tags with spaces or commas",
  "ui.field.text": "Text",
  "ui.field.title": "Title",
  "ui.field.to": "To",
  "ui.field.url": "URL",
  "ui.field.username": "Username",
  "ui.field.visibility": "Visibility",
  "ui.messages.dialog.add": "Add message",
  "ui.messages.dialog.edit": "Edit message",
  "ui.messages.empty": "No messages",
  "ui.messages.filter.all-languages": "All languages",
  "ui.messages.search.placeholder": "Search messages",
  "ui.my-reports.dialog.edit": "Edit report",
  "ui.my-reports.empty": "No reports yet",
  "ui.my-reports.filter.all-statuses": "All statuses",
  "ui.nav.admin": "Admin",
  "ui.nav.my-bookmarks": "My bookmarks",
  "ui.nav.my-reports": "My reports",
  "ui.nav.public-feed": "Public feed",
  "ui.nav.tags": "Tags",
  "ui.report.reason.broken-link": "Broken link",
  "ui.report.reason.offensive": "Offensive",
  "ui.report.reason.other": "Other",
  "ui.report.reason.spam": "Spam",
  "ui.report.reported": "Reported",
  "ui.report.status.actioned": "Actioned",
  "ui.report.status.dismissed": "Dismissed",
  "ui.report.status.open": "Open",
  "ui.reports.bookmark-unavailable": "Bookmark unavailable",
  "ui.reports.empty": "No reports to review",
  "ui.theme.auto": "Auto",
  "ui.theme.dark": "Dark",
  "ui.theme.label": "Theme",
  "ui.theme.light": "Light",
  "ui.toast.bookmark-deleted": "Bookmark deleted",
  "ui.toast.message-created": "Message created",
  "ui.toast.message-deleted": "Message deleted",
  "ui.toast.message-updated": "Message updated",
  "ui.toast.report-duplicate": "Already reported",
  "ui.toast.report-submitted": "Report submitted",
  "ui.toast.report-updated": "Report updated",
  "ui.toast.report-withdrawn": "Report withdrawn",
  "ui.user.status.active": "Active",
  "ui.user.status.blocked": "Blocked",
  "ui.users.search.placeholder": "Search users",
  "ui.visibility.private": "Private",
  "ui.visibility.public": "Public",
  "validation.block.reason.required": "A reason is required",
};

export function seedMessages(messages: Record<string, string> = {}): void {
  i18n.set({
    lang: "en",
    resolvedLanguage: "en",
    messages: { ...defaultMessages, ...messages },
    ready: true,
  });
  document.documentElement.lang = "en";
}

export function setIdentity(user: User | null): void {
  if (user) {
    session.set({ authenticated: true, username: user.username });
    me.set(user);
  } else {
    session.set({ authenticated: false });
    me.set(null);
  }
}

export function bookmark(overrides: Partial<Bookmark> = {}): Bookmark {
  return {
    id: "00000000-0000-4000-8000-000000000101",
    owner: "alice",
    url: "https://example.com/svelte",
    title: "Svelte guide",
    notes: "A useful reference",
    tags: ["svelte"],
    visibility: "public",
    status: "active",
    createdAt: timestamp,
    updatedAt: timestamp,
    ...overrides,
  };
}

export function report(overrides: Partial<Report> = {}): Report {
  return {
    id: "00000000-0000-4000-8000-000000000301",
    bookmarkId: "00000000-0000-4000-8000-000000000101",
    reporter: "demo",
    reason: "spam",
    comment: "Suspicious content",
    status: "open",
    createdAt: timestamp,
    ...overrides,
  };
}

export function page<T>(items: T[], pageNumber = 0): Page<T> {
  return {
    items,
    page: pageNumber,
    size: 20,
    totalItems: items.length,
    totalPages: 1,
  };
}

export function problem(
  status: number,
  detail: string,
  errors?: { field: string; messageKey: string; message: string }[],
): Response {
  return Response.json(
    { title: detail, status, detail, ...(errors ? { errors } : {}) },
    { status, headers: { "Content-Type": "application/problem+json" } },
  );
}

export function stubFetch(
  handler: (request: Request) => Response | Promise<Response>,
): Request[] {
  const requests: Request[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const request =
        input instanceof Request ? input : new Request(input, init);
      requests.push(request);
      return handler(request);
    }),
  );
  return requests;
}

export function installDialogPolyfill(): void {
  Object.defineProperty(HTMLDialogElement.prototype, "showModal", {
    configurable: true,
    value(this: HTMLDialogElement) {
      this.open = true;
    },
  });
  Object.defineProperty(HTMLDialogElement.prototype, "close", {
    configurable: true,
    value(this: HTMLDialogElement) {
      this.open = false;
      this.dispatchEvent(new Event("close"));
    },
  });
}

export async function settle(): Promise<void> {
  await Promise.resolve();
  await tick();
  await Promise.resolve();
  await tick();
}

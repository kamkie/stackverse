import { ApiError, fieldErrorFor, messageOf } from "./api";
import { APP_ACTIONS } from "./app-actions";
import { localizeFieldError } from "./i18n";
import {
  i18n,
  state,
  REPORTED_STORAGE_KEY,
  THEME_STORAGE_KEY,
} from "./app-state";
import type { ReportStatus, User } from "./types";

const REPORT_STATUSES: readonly ReportStatus[] = [
  "open",
  "dismissed",
  "actioned",
];

export function t(key: string): string {
  return i18n.t(key);
}

export function escapeHtml(value: unknown): string {
  return String(value ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

export function selected(value: string, expected: string): string {
  return value === expected ? " selected" : "";
}

export function reportStatusOptionsHtml(value: ReportStatus | ""): string {
  return REPORT_STATUSES.map(
    (status) =>
      `<option value="${status}"${selected(value, status)}>${escapeHtml(t(`ui.report.status.${status}`))}</option>`,
  ).join("");
}

export function reportStatusBadgeHtml(status: ReportStatus): string {
  return `<span class="sv-badge${status === "actioned" ? " sv-badge--danger" : ""}">${escapeHtml(t(`ui.report.status.${status}`))}</span>`;
}

export function pathForApi(path: string, value: string): string {
  return `${path}/${encodeURIComponent(value)}`;
}

export function isAdmin(user: User | null): boolean {
  return user?.roles.includes("admin") ?? false;
}

export function isModerator(user: User | null): boolean {
  return (
    user?.roles.includes("moderator") || user?.roles.includes("admin") || false
  );
}

export function currentPath(): string {
  return window.location.pathname === "/" ? "/feed" : window.location.pathname;
}

export function navClass(path: string, exact = false): string {
  const current = currentPath();
  const active = exact
    ? current === path
    : current === path || current.startsWith(`${path}/`);
  return `sv-nav-link${active ? " is-active" : ""}`;
}

export function readStoredTheme(): "auto" | "light" | "dark" {
  try {
    const stored = localStorage.getItem(THEME_STORAGE_KEY);
    return stored === "light" || stored === "dark" ? stored : "auto";
  } catch {
    return "auto";
  }
}

export function applyTheme(theme: "auto" | "light" | "dark"): void {
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

export function readReportedIds(): Set<string> {
  try {
    const raw = sessionStorage.getItem(REPORTED_STORAGE_KEY);
    return new Set(raw ? (JSON.parse(raw) as string[]) : []);
  } catch {
    return new Set();
  }
}

export function writeReportedIds(ids: Set<string>): void {
  try {
    sessionStorage.setItem(REPORTED_STORAGE_KEY, JSON.stringify([...ids]));
  } catch {
    // Session memory is a convenience.
  }
}

export function addReportedId(id: string): void {
  const ids = readReportedIds();
  ids.add(id);
  writeReportedIds(ids);
}

export function removeReportedId(id: string): void {
  const ids = readReportedIds();
  ids.delete(id);
  writeReportedIds(ids);
}

export function toastHtml(): string {
  return `<div class="sv-toast-region" role="status" aria-live="polite">${state.toasts
    .map(
      (toast) =>
        `<div class="sv-toast sv-toast--${toast.variant}">${escapeHtml(toast.message)}</div>`,
    )
    .join("")}</div>`;
}

export function loadingHtml(): string {
  return `<div class="sv-loading" role="status"><span class="sv-spinner"></span></div>`;
}

export function errorHtml(error: unknown): string {
  if (error instanceof ApiError && error.status === 401)
    return loginPromptHtml();
  return `<div class="sv-alert sv-alert--danger" role="alert">${escapeHtml(messageOf(error))}</div>`;
}

export function loginPromptHtml(): string {
  return `<div class="sv-empty"><a class="sv-button sv-button--primary" href="/auth/login">${escapeHtml(t("ui.action.login"))}</a></div>`;
}

export function paginationHtml(
  page: number,
  totalPages: number,
  bind: string,
): string {
  if (totalPages <= 1) return "";
  return `<nav class="sv-pagination">
    <button type="button" class="sv-button sv-button--ghost sv-button--sm" aria-label="${escapeHtml(t("ui.action.previous"))}" data-action="${APP_ACTIONS.page}" data-bind="${bind}" data-page="${page - 1}"${page <= 0 ? " disabled" : ""}>&lsaquo;</button>
    <span>${page + 1} / ${totalPages}</span>
    <button type="button" class="sv-button sv-button--ghost sv-button--sm" aria-label="${escapeHtml(t("ui.action.next"))}" data-action="${APP_ACTIONS.page}" data-bind="${bind}" data-page="${page + 1}"${page >= totalPages - 1 ? " disabled" : ""}>&rsaquo;</button>
  </nav>`;
}

export function fieldError(error: unknown, field: string): string | undefined {
  const entry = fieldErrorFor(error, field);
  return entry ? localizeFieldError(entry, t) : undefined;
}

export function textFieldHtml({
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
  error?: string | undefined;
  type?: string | undefined;
  hint?: string | undefined;
}): string {
  const id = `field-${name}`;
  const errorId = `${id}-error`;
  const hintId = `${id}-hint`;
  const describedBy = [hint ? hintId : "", error ? errorId : ""]
    .filter(Boolean)
    .join(" ");
  return `<div class="sv-field${error ? " is-invalid" : ""}">
    <label class="sv-label" for="${id}">${escapeHtml(label)}</label>
    <input id="${id}" name="${escapeHtml(name)}" type="${escapeHtml(type)}" class="sv-input" value="${escapeHtml(value)}"${describedBy ? ` aria-describedby="${describedBy}"` : ""}${error ? ' aria-invalid="true"' : ""}>
    ${hint ? `<span class="sv-field-hint" id="${hintId}">${escapeHtml(hint)}</span>` : ""}
    ${error ? `<span class="sv-field-error" id="${errorId}">${escapeHtml(error)}</span>` : ""}
  </div>`;
}

export function textareaFieldHtml({
  name,
  label,
  value,
  error,
}: {
  name: string;
  label: string;
  value: string;
  error?: string | undefined;
}): string {
  const id = `field-${name}`;
  const errorId = `${id}-error`;
  return `<div class="sv-field${error ? " is-invalid" : ""}">
    <label class="sv-label" for="${id}">${escapeHtml(label)}</label>
    <textarea id="${id}" name="${escapeHtml(name)}" class="sv-textarea"${error ? ` aria-describedby="${errorId}" aria-invalid="true"` : ""}>${escapeHtml(value)}</textarea>
    ${error ? `<span class="sv-field-error" id="${errorId}">${escapeHtml(error)}</span>` : ""}
  </div>`;
}

export function selectFieldHtml({
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
  error?: string | undefined;
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

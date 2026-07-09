import { apiGet } from "./api";
import { i18n, state, SUPPORTED_LANGUAGES } from "./app-state";
import {
  bookmarkCellHtml,
  bookmarkContextMap,
  tagListHtml,
} from "./bookmark-pages";
import {
  t,
  escapeHtml,
  selected,
  isAdmin,
  isModerator,
  navClass,
  loginPromptHtml,
  paginationHtml,
} from "./view-helpers";
import type {
  AdminStats,
  AuditEntry,
  Message,
  Page,
  Report,
  ReportStatus,
  UserAccount,
} from "./types";

export async function adminPageHtml(path: string): Promise<string> {
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

export async function adminContentHtml(path: string): Promise<string> {
  if (path === "/admin/reports") return adminReportsPageHtml();
  if (path === "/admin/users") return usersPageHtml();
  if (path === "/admin/audit") return auditPageHtml();
  if (path === "/admin/messages") return messagesPageHtml();
  return dashboardPageHtml();
}

export function dailyChartHtml(daily: AdminStats["daily"]): string {
  const width = 620;
  const height = 160;
  const left = 24;
  const bottom = 18;
  const top = 6;
  const chartHeight = height - bottom - top;
  const max = Math.max(
    1,
    ...daily.map((day) => Math.max(day.bookmarksCreated, day.activeUsers)),
  );
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

export async function dashboardPageHtml(): Promise<string> {
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

export async function adminReportsPageHtml(): Promise<string> {
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

export async function usersPageHtml(): Promise<string> {
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

export function endOfDayIso(day: string): string {
  return new Date(`${day}T23:59:59.999`)
    .toISOString()
    .replace(".999Z", ".999999Z");
}

export async function auditPageHtml(): Promise<string> {
  const data = await apiGet<Page<AuditEntry>>("/api/v1/admin/audit-log", {
    ...(state.audit.actor ? { actor: state.audit.actor } : {}),
    ...(state.audit.action ? { action: state.audit.action } : {}),
    ...(state.audit.from
      ? { from: new Date(`${state.audit.from}T00:00:00`).toISOString() }
      : {}),
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

export async function messagesPageHtml(): Promise<string> {
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

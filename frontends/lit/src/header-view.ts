import { i18n, state, SUPPORTED_LANGUAGES, THEME_OPTIONS } from "./app-state";
import {
  t,
  escapeHtml,
  isModerator,
  navClass,
  readStoredTheme,
} from "./view-helpers";

export function headerHtml(): string {
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

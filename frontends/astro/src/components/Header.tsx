import { createSignal, For, onMount, Show } from "solid-js";
import { initializeClient } from "../lib/initializeClient";
import { i18n, m, setLanguage, SUPPORTED_LANGUAGES } from "../lib/i18n";
import { LOGIN_URL, isModerator, logout, me, session } from "../lib/session";
import { applyTheme, readStoredTheme, THEME_OPTIONS, type ThemeOption } from "../lib/theme";

interface Props { currentPath: string }

export default function Header(props: Props) {
  const [theme, setTheme] = createSignal<ThemeOption>(readStoredTheme());
  const navClass = (path: string, admin = false) => `sv-nav-link${(admin ? props.currentPath.startsWith("/admin") : props.currentPath === path) ? " is-active" : ""}`;

  onMount(() => {
    void initializeClient();
    if (import.meta.env.DEV) {
      void import("../dev/forwardConsoleToDevServer").then(({ forwardConsoleToDevServer }) => forwardConsoleToDevServer());
      void import("../dev/logUserActions").then(({ logUserActions }) => logUserActions());
    }
  });

  async function doLogout() {
    await logout();
    location.assign("/feed");
  }

  return (
    <header class="sv-header">
      <a href="/feed" class="sv-brand">{m(i18n(), "ui.app.title")}</a>
      <nav class="sv-nav">
        <Show when={session()?.authenticated}>
          <a href="/bookmarks" class={navClass("/bookmarks")}>{m(i18n(), "ui.nav.my-bookmarks")}</a>
          <a href="/reports" class={navClass("/reports")}>{m(i18n(), "ui.nav.my-reports")}</a>
        </Show>
        <a href="/feed" class={navClass("/feed")}>{m(i18n(), "ui.nav.public-feed")}</a>
        <Show when={isModerator(me())}><a href="/admin" class={navClass("/admin", true)}>{m(i18n(), "ui.nav.admin")}</a></Show>
      </nav>
      <div class="sv-header-actions">
        <div class="sv-theme-switch" role="group" aria-label={m(i18n(), "ui.theme.label")}>
          <For each={THEME_OPTIONS}>{(option) => <button type="button" class={`sv-theme-option${theme() === option ? " is-active" : ""}`} onClick={() => { setTheme(option); applyTheme(option); }}>{m(i18n(), `ui.theme.${option}`)}</button>}</For>
        </div>
        <div class="sv-lang-switch" role="group" aria-label="language">
          <For each={SUPPORTED_LANGUAGES}>{(code) => <button type="button" lang={code} class={`sv-lang-option${(i18n().lang ?? i18n().resolvedLanguage) === code ? " is-active" : ""}`} onClick={() => setLanguage(code)}>{code.toUpperCase()}</button>}</For>
        </div>
        <Show when={session()?.authenticated} fallback={<a class="sv-button sv-button--primary sv-button--sm" href={LOGIN_URL}>{m(i18n(), "ui.action.login")}</a>}>
          <span class="sv-username">{me()?.username}</span>
          <button type="button" class="sv-button sv-button--ghost sv-button--sm" onClick={doLogout}>{m(i18n(), "ui.action.logout")}</button>
        </Show>
      </div>
    </header>
  );
}

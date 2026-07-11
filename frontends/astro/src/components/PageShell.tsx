import { createSignal, onCleanup, onMount, For, Show, type JSX } from "solid-js";
import ToastRegion, { type Toast } from "./ToastRegion";
import { i18n, loadBundle, m, setLanguage, SUPPORTED_LANGUAGES } from "../lib/i18n";
import {
  LOGIN_URL,
  expireSession,
  isAdmin,
  isModerator,
  logout,
  me,
  refreshSession,
  session,
} from "../lib/session";
import { applyTheme, readStoredTheme, THEME_OPTIONS, type ThemeOption } from "../lib/theme";
import type { Session } from "../lib/types";

export type ToastFn = (message: string, tone?: "success" | "danger") => void;

interface Props {
  activePath: string;
  admin?: boolean;
  requiredRole?: "moderator" | "admin";
  requiresAuth?: boolean;
  content: (toast: ToastFn) => JSX.Element;
  anonymousContent?: (toast: ToastFn) => JSX.Element;
}

function currentSession(): Session {
  return session() ?? { authenticated: false };
}

function currentUsername(): string {
  const current = currentSession();
  return current.authenticated ? current.username : "";
}

export default function PageShell(props: Props) {
  const [theme, setTheme] = createSignal<ThemeOption>(readStoredTheme());
  const [toasts, setToasts] = createSignal<Toast[]>([]);
  let toastId = 0;

  function onUnauthorized() {
    expireSession();
    location.assign("/feed");
  }

  function navClass(path: string, section?: "admin"): string {
    const active = section === "admin" ? props.activePath.startsWith("/admin") : props.activePath === path;
    return `sv-nav-link${active ? " is-active" : ""}`;
  }

  function showToast(message: string, tone: "success" | "danger" = "success") {
    const toast = { id: ++toastId, message, tone };
    setToasts((current) => [...current, toast]);
    window.setTimeout(() => setToasts((current) => current.filter((item) => item.id !== toast.id)), 3500);
  }

  function pageContent() {
    if (props.admin) {
      if (!isModerator(me())) return <div class="sv-alert sv-alert--danger" role="alert">403</div>;
      if (props.requiredRole === "admin" && !isAdmin(me())) return <div class="sv-alert sv-alert--danger" role="alert">403</div>;
      return (
        <div class="sv-layout">
          <aside class="sv-sidebar">
            <h2 class="sv-sidebar-title">{m(i18n(), "ui.nav.admin")}</h2>
            <nav class="sv-nav sv-nav--vertical" aria-label="Admin">
              <a href="/admin" class={navClass("/admin")}>{m(i18n(), "ui.admin.dashboard")}</a>
              <a href="/admin/reports" class={navClass("/admin/reports")}>{m(i18n(), "ui.admin.reports")}</a>
              <Show when={isAdmin(me())}>
                <a href="/admin/users" class={navClass("/admin/users")}>{m(i18n(), "ui.admin.users")}</a>
                <a href="/admin/audit" class={navClass("/admin/audit")}>{m(i18n(), "ui.admin.audit")}</a>
                <a href="/admin/messages" class={navClass("/admin/messages")}>{m(i18n(), "ui.admin.messages")}</a>
              </Show>
            </nav>
          </aside>
          <section class="sv-content">{props.content(showToast)}</section>
        </div>
      );
    }
    if (props.requiresAuth && !currentSession().authenticated) {
      return props.anonymousContent?.(showToast) ?? <div class="sv-alert sv-alert--danger" role="alert">401</div>;
    }
    return props.content(showToast);
  }

  async function doLogout() {
    await logout();
    location.assign("/feed");
  }

  onMount(() => {
    window.addEventListener("stackverse:unauthorized", onUnauthorized);
    void loadBundle();
    void refreshSession();
  });
  onCleanup(() => window.removeEventListener("stackverse:unauthorized", onUnauthorized));

  return (
    <Show when={i18n().ready && session() !== null} fallback={<div class="sv-loading"><span class="sv-spinner" /></div>}>
      <div class="sv-app">
        <header class="sv-header">
          <a href="/feed" class="sv-brand">{m(i18n(), "ui.app.title")}</a>
          <nav class="sv-nav">
            <Show when={currentSession().authenticated}>
              <a href="/bookmarks" class={navClass("/bookmarks")}>{m(i18n(), "ui.nav.my-bookmarks")}</a>
              <a href="/reports" class={navClass("/reports")}>{m(i18n(), "ui.nav.my-reports")}</a>
            </Show>
            <a href="/feed" class={navClass("/feed")}>{m(i18n(), "ui.nav.public-feed")}</a>
            <Show when={isModerator(me())}>
              <a href="/admin" class={navClass("/admin", "admin")}>{m(i18n(), "ui.nav.admin")}</a>
            </Show>
          </nav>
          <div class="sv-header-actions">
            <div class="sv-theme-switch" role="group" aria-label={m(i18n(), "ui.theme.label")}>
              <For each={THEME_OPTIONS}>{(option) => (
                <button type="button" class={`sv-theme-option${theme() === option ? " is-active" : ""}`} onClick={() => { setTheme(option); applyTheme(option); }}>
                  {m(i18n(), `ui.theme.${option}`)}
                </button>
              )}</For>
            </div>
            <div class="sv-lang-switch" role="group" aria-label="language">
              <For each={SUPPORTED_LANGUAGES}>{(code) => (
                <button type="button" lang={code} class={`sv-lang-option${(i18n().lang ?? i18n().resolvedLanguage) === code ? " is-active" : ""}`} onClick={() => setLanguage(code)}>
                  {code.toUpperCase()}
                </button>
              )}</For>
            </div>
            <Show when={currentSession().authenticated} fallback={<a class="sv-button sv-button--primary sv-button--sm" href={LOGIN_URL}>{m(i18n(), "ui.action.login")}</a>}>
              <span class="sv-username">{currentUsername()}</span>
              <button type="button" class="sv-button sv-button--ghost sv-button--sm" onClick={doLogout}>{m(i18n(), "ui.action.logout")}</button>
            </Show>
          </div>
        </header>
        <main class="sv-main">{pageContent()}</main>
        <ToastRegion toasts={toasts()} />
      </div>
    </Show>
  );
}

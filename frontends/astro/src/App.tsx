import { createSignal, onCleanup, onMount, For, Show } from "solid-js";
import ToastRegion, { type Toast } from "./components/ToastRegion";
import { i18n, loadBundle, m, setLanguage, SUPPORTED_LANGUAGES } from "./lib/i18n";
import { goto, installRouteListener, route } from "./lib/route";
import {
  LOGIN_URL,
  isAdmin,
  isModerator,
  logout,
  expireSession,
  me,
  refreshSession,
  session,
} from "./lib/session";
import { applyTheme, readStoredTheme, THEME_OPTIONS, type ThemeOption } from "./lib/theme";
import type { Session } from "./lib/types";
import MyBookmarksPage from "./screens/MyBookmarksPage";
import MyReportsPage from "./screens/MyReportsPage";
import PublicFeedPage from "./screens/PublicFeedPage";
import AuditLogPage from "./screens/admin/AuditLogPage";
import DashboardPage from "./screens/admin/DashboardPage";
import MessagesPage from "./screens/admin/MessagesPage";
import ReportsPage from "./screens/admin/ReportsPage";
import UsersPage from "./screens/admin/UsersPage";

function currentSession(): Session {
  return session() ?? { authenticated: false };
}

function currentUsername(): string {
  const current = currentSession();
  return current.authenticated ? current.username : "";
}

export default function App() {
  const [theme, setTheme] = createSignal<ThemeOption>(readStoredTheme());
  const [toasts, setToasts] = createSignal<Toast[]>([]);
  let toastId = 0;
  let uninstallRouteListener: (() => void) | undefined;

  function onUnauthorized() {
    expireSession();
    goto("/feed");
  }

  function navClass(path: string, exact = true): string {
    const currentRoute = route();
    const active = exact
      ? currentRoute === path
      : currentRoute === path || currentRoute.startsWith(`${path}/`);
    return `sv-nav-link${active ? " is-active" : ""}`;
  }

  function navigate(path: string): void {
    goto(path);
  }

  function navigateClick(path: string) {
    return (event: MouseEvent) => {
      event.preventDefault();
      navigate(path);
    };
  }

  function changeTheme(next: ThemeOption) {
    setTheme(next);
    applyTheme(next);
  }

  function showToast(message: string, tone: "success" | "danger" = "success") {
    const toast = { id: ++toastId, message, tone };
    setToasts((current) => [...current, toast]);
    window.setTimeout(() => {
      setToasts((current) => current.filter((item) => item.id !== toast.id));
    }, 3500);
  }

  async function doLogout() {
    await logout();
    goto("/feed");
  }

  function adminContent() {
    if (!isModerator(me())) {
      return <div class="sv-alert sv-alert--danger" role="alert">403</div>;
    }
    return (
      <div class="sv-layout">
        <aside class="sv-sidebar">
          <h2 class="sv-sidebar-title">{m(i18n(), "ui.nav.admin")}</h2>
          <nav class="sv-nav sv-nav--vertical" aria-label="Admin">
            <a href="/admin" class={navClass("/admin")} onClick={navigateClick("/admin")}>
              {m(i18n(), "ui.admin.dashboard")}
            </a>
            <a href="/admin/reports" class={navClass("/admin/reports")} onClick={navigateClick("/admin/reports")}>
              {m(i18n(), "ui.admin.reports")}
            </a>
            <Show when={isAdmin(me())}>
              <a href="/admin/users" class={navClass("/admin/users")} onClick={navigateClick("/admin/users")}>
                {m(i18n(), "ui.admin.users")}
              </a>
              <a href="/admin/audit" class={navClass("/admin/audit")} onClick={navigateClick("/admin/audit")}>
                {m(i18n(), "ui.admin.audit")}
              </a>
              <a href="/admin/messages" class={navClass("/admin/messages")} onClick={navigateClick("/admin/messages")}>
                {m(i18n(), "ui.admin.messages")}
              </a>
            </Show>
          </nav>
        </aside>
        <section class="sv-content">
          {route() === "/admin/reports" ? (
            <ReportsPage />
          ) : route() === "/admin/users" && isAdmin(me()) ? (
            <UsersPage />
          ) : route() === "/admin/audit" && isAdmin(me()) ? (
            <AuditLogPage />
          ) : route() === "/admin/messages" && isAdmin(me()) ? (
            <MessagesPage toast={showToast} />
          ) : route() === "/admin" || route() === "/admin/" ? (
            <DashboardPage />
          ) : (
            <div class="sv-alert sv-alert--danger" role="alert">403</div>
          )}
        </section>
      </div>
    );
  }

  function routeContent() {
    if (route() === "/bookmarks") {
      return currentSession().authenticated ? (
        <MyBookmarksPage toast={showToast} />
      ) : (
        <PublicFeedPage toast={showToast} />
      );
    }
    if (route() === "/reports") {
      return currentSession().authenticated ? (
        <MyReportsPage toast={showToast} />
      ) : (
        <PublicFeedPage toast={showToast} />
      );
    }
    if (route().startsWith("/admin")) return adminContent();
    return <PublicFeedPage toast={showToast} />;
  }

  onMount(() => {
    uninstallRouteListener = installRouteListener();
    window.addEventListener("stackverse:unauthorized", onUnauthorized);
    void loadBundle();
    void refreshSession();
  });

  onCleanup(() => {
    uninstallRouteListener?.();
    window.removeEventListener("stackverse:unauthorized", onUnauthorized);
  });

  return (
    <Show
      when={i18n().ready && session() !== null}
      fallback={<div class="sv-loading"><span class="sv-spinner" /></div>}
    >
      <div class="sv-app">
        <header class="sv-header">
          <a href="/" class="sv-brand" onClick={navigateClick("/feed")}>
            {m(i18n(), "ui.app.title")}
          </a>
          <nav class="sv-nav">
            <Show when={currentSession().authenticated}>
              <a href="/bookmarks" class={navClass("/bookmarks")} onClick={navigateClick("/bookmarks")}>
                {m(i18n(), "ui.nav.my-bookmarks")}
              </a>
              <a href="/reports" class={navClass("/reports")} onClick={navigateClick("/reports")}>
                {m(i18n(), "ui.nav.my-reports")}
              </a>
            </Show>
            <a href="/feed" class={navClass("/feed")} onClick={navigateClick("/feed")}>
              {m(i18n(), "ui.nav.public-feed")}
            </a>
            <Show when={isModerator(me())}>
              <a href="/admin" class={navClass("/admin", false)} onClick={navigateClick("/admin")}>
                {m(i18n(), "ui.nav.admin")}
              </a>
            </Show>
          </nav>
          <div class="sv-header-actions">
            <div class="sv-theme-switch" role="group" aria-label={m(i18n(), "ui.theme.label")}>
              <For each={THEME_OPTIONS}>
                {(option) => (
                  <button
                    type="button"
                    class={`sv-theme-option${theme() === option ? " is-active" : ""}`}
                    onClick={() => changeTheme(option)}
                  >
                    {m(i18n(), `ui.theme.${option}`)}
                  </button>
                )}
              </For>
            </div>
            <div class="sv-lang-switch" role="group" aria-label="language">
              <For each={SUPPORTED_LANGUAGES}>
                {(code) => (
                  <button
                    type="button"
                    lang={code}
                    class={`sv-lang-option${(i18n().lang ?? i18n().resolvedLanguage) === code ? " is-active" : ""}`}
                    onClick={() => setLanguage(code)}
                  >
                    {code.toUpperCase()}
                  </button>
                )}
              </For>
            </div>
            <Show
              when={currentSession().authenticated}
              fallback={
                <a class="sv-button sv-button--primary sv-button--sm" href={LOGIN_URL}>
                  {m(i18n(), "ui.action.login")}
                </a>
              }
            >
              <span class="sv-username">{currentUsername()}</span>
              <button type="button" class="sv-button sv-button--ghost sv-button--sm" onClick={doLogout}>
                {m(i18n(), "ui.action.logout")}
              </button>
            </Show>
          </div>
        </header>

        <main class="sv-main">{routeContent()}</main>
        <ToastRegion toasts={toasts()} />
      </div>
    </Show>
  );
}

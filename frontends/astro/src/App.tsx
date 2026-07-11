import { createSignal, onCleanup, onMount, For, Show } from "solid-js";
import ToastRegion, { type Toast } from "./components/ToastRegion";
import { i18n, loadBundle, m, setLanguage, SUPPORTED_LANGUAGES } from "./lib/i18n";
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

export type PageId =
  | "feed"
  | "bookmarks"
  | "reports"
  | "admin-dashboard"
  | "admin-reports"
  | "admin-users"
  | "admin-audit"
  | "admin-messages";

interface Props {
  page: PageId;
}

function currentSession(): Session {
  return session() ?? { authenticated: false };
}

function currentUsername(): string {
  const current = currentSession();
  return current.authenticated ? current.username : "";
}

export default function App(props: Props) {
  const [theme, setTheme] = createSignal<ThemeOption>(readStoredTheme());
  const [toasts, setToasts] = createSignal<Toast[]>([]);
  let toastId = 0;

  function onUnauthorized() {
    expireSession();
    location.assign("/feed");
  }

  function navClass(page: PageId, section?: "admin"): string {
    const active = section === "admin" ? props.page.startsWith("admin-") : props.page === page;
    return `sv-nav-link${active ? " is-active" : ""}`;
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
    location.assign("/feed");
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
            <a href="/admin" class={navClass("admin-dashboard")}>
              {m(i18n(), "ui.admin.dashboard")}
            </a>
            <a href="/admin/reports" class={navClass("admin-reports")}>
              {m(i18n(), "ui.admin.reports")}
            </a>
            <Show when={isAdmin(me())}>
              <a href="/admin/users" class={navClass("admin-users")}>
                {m(i18n(), "ui.admin.users")}
              </a>
              <a href="/admin/audit" class={navClass("admin-audit")}>
                {m(i18n(), "ui.admin.audit")}
              </a>
              <a href="/admin/messages" class={navClass("admin-messages")}>
                {m(i18n(), "ui.admin.messages")}
              </a>
            </Show>
          </nav>
        </aside>
        <section class="sv-content">
          {props.page === "admin-reports" ? (
            <ReportsPage />
          ) : props.page === "admin-users" && isAdmin(me()) ? (
            <UsersPage />
          ) : props.page === "admin-audit" && isAdmin(me()) ? (
            <AuditLogPage />
          ) : props.page === "admin-messages" && isAdmin(me()) ? (
            <MessagesPage toast={showToast} />
          ) : props.page === "admin-dashboard" ? (
            <DashboardPage />
          ) : (
            <div class="sv-alert sv-alert--danger" role="alert">403</div>
          )}
        </section>
      </div>
    );
  }

  function routeContent() {
    if (props.page === "bookmarks") {
      return currentSession().authenticated ? (
        <MyBookmarksPage toast={showToast} />
      ) : (
        <PublicFeedPage toast={showToast} />
      );
    }
    if (props.page === "reports") {
      return currentSession().authenticated ? (
        <MyReportsPage toast={showToast} />
      ) : (
        <PublicFeedPage toast={showToast} />
      );
    }
    if (props.page.startsWith("admin-")) return adminContent();
    return <PublicFeedPage toast={showToast} />;
  }

  onMount(() => {
    window.addEventListener("stackverse:unauthorized", onUnauthorized);
    void loadBundle();
    void refreshSession();
  });

  onCleanup(() => {
    window.removeEventListener("stackverse:unauthorized", onUnauthorized);
  });

  return (
    <Show
      when={i18n().ready && session() !== null}
      fallback={<div class="sv-loading"><span class="sv-spinner" /></div>}
    >
      <div class="sv-app">
        <header class="sv-header">
          <a href="/feed" class="sv-brand">
            {m(i18n(), "ui.app.title")}
          </a>
          <nav class="sv-nav">
            <Show when={currentSession().authenticated}>
              <a href="/bookmarks" class={navClass("bookmarks")}>
                {m(i18n(), "ui.nav.my-bookmarks")}
              </a>
              <a href="/reports" class={navClass("reports")}>
                {m(i18n(), "ui.nav.my-reports")}
              </a>
            </Show>
            <a href="/feed" class={navClass("feed")}>
              {m(i18n(), "ui.nav.public-feed")}
            </a>
            <Show when={isModerator(me())}>
              <a href="/admin" class={navClass("admin-dashboard", "admin")}>
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

import { $, component$, useStore, useVisibleTask$ } from "@builder.io/qwik";
import ToastRegion, { type Toast } from "./components/ToastRegion";
import {
  initialI18nState,
  loadBundle,
  m,
  setLanguage,
  SUPPORTED_LANGUAGES,
  type I18nState,
} from "./lib/i18n";
import { currentPath, goto, installRouteListener } from "./lib/route";
import {
  LOGIN_URL,
  isAdmin,
  isModerator,
  logout,
  refreshSession,
} from "./lib/session";
import {
  applyTheme,
  readStoredTheme,
  THEME_OPTIONS,
  type ThemeOption,
} from "./lib/theme";
import type { Session, User } from "./lib/types";
import MyBookmarksPage from "./pages/MyBookmarksPage";
import MyReportsPage from "./pages/MyReportsPage";
import PublicFeedPage from "./pages/PublicFeedPage";
import AuditLogPage from "./pages/admin/AuditLogPage";
import DashboardPage from "./pages/admin/DashboardPage";
import MessagesPage from "./pages/admin/MessagesPage";
import ReportsPage from "./pages/admin/ReportsPage";
import UsersPage from "./pages/admin/UsersPage";

interface AppState {
  i18n: I18nState;
  session: Session | null;
  me: User | null | undefined;
  route: string;
  theme: ThemeOption;
  toasts: Toast[];
  toastId: number;
}

function anonymousSession(): Session {
  return { authenticated: false };
}

function username(session: Session): string {
  return session.authenticated ? session.username : "";
}

export default component$(() => {
  const app = useStore<AppState>({
    i18n: initialI18nState,
    session: null,
    me: undefined,
    route: currentPath(),
    theme: readStoredTheme(),
    toasts: [],
    toastId: 0,
  });

  const showToast$ = $(
    (message: string, tone: "success" | "danger" = "success") => {
      const id = ++app.toastId;
      app.toasts = [...app.toasts, { id, message, tone }];
      window.setTimeout(() => {
        app.toasts = app.toasts.filter((item) => item.id !== id);
      }, 3500);
    },
  );

  const navigate$ = $((path: string, replace = false) => {
    app.route = goto(path, replace);
  });

  const updateBundle$ = $((next: I18nState) => {
    app.i18n = next;
  });

  const navClass = (path: string, exact = true): string => {
    const active = exact
      ? app.route === path
      : app.route === path || app.route.startsWith(`${path}/`);
    return `sv-nav-link${active ? " is-active" : ""}`;
  };

  const currentSession = app.session ?? anonymousSession();

  useVisibleTask$(({ cleanup }) => {
    const uninstall = installRouteListener((next) => {
      app.route = next;
    });
    void loadBundle().then((next) => {
      app.i18n = next;
    });
    void refreshSession().then((next) => {
      app.session = next.session;
      app.me = next.me;
    });
    cleanup(uninstall);
  });

  const adminContent = () => {
    if (!isModerator(app.me)) {
      return (
        <div class="sv-alert sv-alert--danger" role="alert">
          403
        </div>
      );
    }
    return (
      <div class="sv-layout">
        <aside class="sv-sidebar">
          <h2 class="sv-sidebar-title">{m(app.i18n, "ui.nav.admin")}</h2>
          <nav class="sv-nav sv-nav--vertical" aria-label="Admin">
            <a
              href="/admin"
              class={navClass("/admin")}
              onClick$={(event: Event) => {
                event.preventDefault();
                void navigate$("/admin");
              }}
            >
              {m(app.i18n, "ui.admin.dashboard")}
            </a>
            <a
              href="/admin/reports"
              class={navClass("/admin/reports")}
              onClick$={(event: Event) => {
                event.preventDefault();
                void navigate$("/admin/reports");
              }}
            >
              {m(app.i18n, "ui.admin.reports")}
            </a>
            {isAdmin(app.me) ? (
              <>
                <a
                  href="/admin/users"
                  class={navClass("/admin/users")}
                  onClick$={(event: Event) => {
                    event.preventDefault();
                    void navigate$("/admin/users");
                  }}
                >
                  {m(app.i18n, "ui.admin.users")}
                </a>
                <a
                  href="/admin/audit"
                  class={navClass("/admin/audit")}
                  onClick$={(event: Event) => {
                    event.preventDefault();
                    void navigate$("/admin/audit");
                  }}
                >
                  {m(app.i18n, "ui.admin.audit")}
                </a>
                <a
                  href="/admin/messages"
                  class={navClass("/admin/messages")}
                  onClick$={(event: Event) => {
                    event.preventDefault();
                    void navigate$("/admin/messages");
                  }}
                >
                  {m(app.i18n, "ui.admin.messages")}
                </a>
              </>
            ) : null}
          </nav>
        </aside>
        <section class="sv-content">
          {app.route === "/admin/reports" ? (
            <ReportsPage i18n={app.i18n} />
          ) : app.route === "/admin/users" && isAdmin(app.me) ? (
            <UsersPage i18n={app.i18n} me={app.me} />
          ) : app.route === "/admin/audit" && isAdmin(app.me) ? (
            <AuditLogPage i18n={app.i18n} />
          ) : app.route === "/admin/messages" && isAdmin(app.me) ? (
            <MessagesPage
              i18n={app.i18n}
              onBundle$={updateBundle$}
              toast$={showToast$}
            />
          ) : app.route === "/admin" || app.route === "/admin/" ? (
            <DashboardPage i18n={app.i18n} onNavigate$={navigate$} />
          ) : (
            <div class="sv-alert sv-alert--danger" role="alert">
              403
            </div>
          )}
        </section>
      </div>
    );
  };

  const routeContent = () => {
    if (app.route === "/bookmarks") {
      return currentSession.authenticated ? (
        <MyBookmarksPage i18n={app.i18n} toast$={showToast$} />
      ) : (
        <PublicFeedPage
          i18n={app.i18n}
          session={app.session}
          toast$={showToast$}
        />
      );
    }
    if (app.route === "/reports") {
      return currentSession.authenticated ? (
        <MyReportsPage i18n={app.i18n} toast$={showToast$} />
      ) : (
        <PublicFeedPage
          i18n={app.i18n}
          session={app.session}
          toast$={showToast$}
        />
      );
    }
    if (app.route.startsWith("/admin")) return adminContent();
    return (
      <PublicFeedPage
        i18n={app.i18n}
        session={app.session}
        toast$={showToast$}
      />
    );
  };

  if (!app.i18n.ready || app.session === null) {
    return (
      <div class="sv-loading">
        <span class="sv-spinner" />
      </div>
    );
  }

  return (
    <div class="sv-app">
      <header class="sv-header">
        <a
          href="/"
          class="sv-brand"
          onClick$={(event: Event) => {
            event.preventDefault();
            void navigate$("/feed");
          }}
        >
          {m(app.i18n, "ui.app.title")}
        </a>
        <nav class="sv-nav">
          {currentSession.authenticated ? (
            <>
              <a
                href="/bookmarks"
                class={navClass("/bookmarks")}
                onClick$={(event: Event) => {
                  event.preventDefault();
                  void navigate$("/bookmarks");
                }}
              >
                {m(app.i18n, "ui.nav.my-bookmarks")}
              </a>
              <a
                href="/reports"
                class={navClass("/reports")}
                onClick$={(event: Event) => {
                  event.preventDefault();
                  void navigate$("/reports");
                }}
              >
                {m(app.i18n, "ui.nav.my-reports")}
              </a>
            </>
          ) : null}
          <a
            href="/feed"
            class={navClass("/feed")}
            onClick$={(event: Event) => {
              event.preventDefault();
              void navigate$("/feed");
            }}
          >
            {m(app.i18n, "ui.nav.public-feed")}
          </a>
          {isModerator(app.me) ? (
            <a
              href="/admin"
              class={navClass("/admin", false)}
              onClick$={(event: Event) => {
                event.preventDefault();
                void navigate$("/admin");
              }}
            >
              {m(app.i18n, "ui.nav.admin")}
            </a>
          ) : null}
        </nav>
        <div class="sv-header-actions">
          <div
            class="sv-theme-switch"
            role="group"
            aria-label={m(app.i18n, "ui.theme.label")}
          >
            {THEME_OPTIONS.map((option) => (
              <button
                key={option}
                type="button"
                class={`sv-theme-option${app.theme === option ? " is-active" : ""}`}
                onClick$={() => {
                  app.theme = option;
                  applyTheme(option);
                }}
              >
                {m(app.i18n, `ui.theme.${option}`)}
              </button>
            ))}
          </div>
          <div class="sv-lang-switch" role="group" aria-label="language">
            {SUPPORTED_LANGUAGES.map((code) => (
              <button
                key={code}
                type="button"
                lang={code}
                class={`sv-lang-option${(app.i18n.lang ?? app.i18n.resolvedLanguage) === code ? " is-active" : ""}`}
                onClick$={async () => {
                  app.i18n = await setLanguage(code);
                }}
              >
                {code.toUpperCase()}
              </button>
            ))}
          </div>
          {currentSession.authenticated ? (
            <>
              <span class="sv-username">{username(currentSession)}</span>
              <button
                type="button"
                class="sv-button sv-button--ghost sv-button--sm"
                onClick$={async () => {
                  await logout();
                  app.session = { authenticated: false };
                  app.me = null;
                  void navigate$("/feed");
                }}
              >
                {m(app.i18n, "ui.action.logout")}
              </button>
            </>
          ) : (
            <a
              class="sv-button sv-button--primary sv-button--sm"
              href={LOGIN_URL}
            >
              {m(app.i18n, "ui.action.login")}
            </a>
          )}
        </div>
      </header>

      <main class="sv-main">{routeContent()}</main>
      <ToastRegion toasts={app.toasts} />
    </div>
  );
});

import { useState } from "react";
import { NavLink, Outlet, Link } from "react-router";
import { LOGIN_URL, useLogout, useSession } from "../auth/session";
import { isModerator, useMe } from "../auth/useMe";
import { useI18n } from "../i18n/I18nProvider";

const SUPPORTED_LANGUAGES = ["en", "pl"] as const;

const THEME_STORAGE_KEY = "stackverse.theme";
const THEME_OPTIONS = ["auto", "light", "dark"] as const;
type ThemeOption = (typeof THEME_OPTIONS)[number];

function readStoredTheme(): ThemeOption {
  try {
    const stored = localStorage.getItem(THEME_STORAGE_KEY);
    return stored === "light" || stored === "dark" ? stored : "auto";
  } catch {
    return "auto";
  }
}

// The persisted choice is applied before first paint by the inline script in
// index.html; tokens.css maps data-theme (or its absence = auto) to colors.
function ThemeSwitcher() {
  const { t } = useI18n();
  const [theme, setTheme] = useState<ThemeOption>(readStoredTheme);

  const applyTheme = (next: ThemeOption) => {
    setTheme(next);
    const root = document.documentElement;
    if (next === "auto") root.removeAttribute("data-theme");
    else root.setAttribute("data-theme", next);
    try {
      if (next === "auto") localStorage.removeItem(THEME_STORAGE_KEY);
      else localStorage.setItem(THEME_STORAGE_KEY, next);
    } catch {
      // storage unavailable — the choice just won't survive a reload
    }
  };

  return (
    <div className="sv-theme-switch" role="group" aria-label={t("ui.theme.label")}>
      {THEME_OPTIONS.map((option) => (
        <button
          key={option}
          type="button"
          className={`sv-theme-option${theme === option ? " is-active" : ""}`}
          onClick={() => applyTheme(option)}
        >
          {t(`ui.theme.${option}`)}
        </button>
      ))}
    </div>
  );
}

function LanguageSwitcher() {
  const { lang, resolvedLanguage, setLang } = useI18n();
  const current = lang ?? resolvedLanguage;
  return (
    <div className="sv-lang-switch" role="group" aria-label="language">
      {SUPPORTED_LANGUAGES.map((code) => (
        <button
          key={code}
          type="button"
          lang={code}
          className={`sv-lang-option${current === code ? " is-active" : ""}`}
          onClick={() => setLang(code)}
        >
          {code.toUpperCase()}
        </button>
      ))}
    </div>
  );
}

function SessionControls() {
  const { t } = useI18n();
  const session = useSession();
  const logout = useLogout();

  if (session.isPending) return null;

  if (session.data?.authenticated) {
    return (
      <>
        <span className="sv-username">{session.data.username}</span>
        <button
          type="button"
          className="sv-button sv-button--ghost sv-button--sm"
          onClick={() => logout.mutate()}
          disabled={logout.isPending}
        >
          {t("ui.action.logout")}
        </button>
      </>
    );
  }

  return (
    <a className="sv-button sv-button--primary sv-button--sm" href={LOGIN_URL}>
      {t("ui.action.login")}
    </a>
  );
}

export function Layout() {
  const { t } = useI18n();
  const me = useMe();
  const navLinkClass = ({ isActive }: { isActive: boolean }) =>
    `sv-nav-link${isActive ? " is-active" : ""}`;

  return (
    <div className="sv-app">
      <header className="sv-header">
        <Link to="/" className="sv-brand">
          {t("ui.app.title")}
        </Link>
        <nav className="sv-nav">
          <NavLink to="/bookmarks" className={navLinkClass}>
            {t("ui.nav.my-bookmarks")}
          </NavLink>
          <NavLink to="/feed" className={navLinkClass}>
            {t("ui.nav.public-feed")}
          </NavLink>
          {isModerator(me.data) && (
            <NavLink to="/admin" className={navLinkClass}>
              {t("ui.nav.admin")}
            </NavLink>
          )}
        </nav>
        <div className="sv-header-actions">
          <ThemeSwitcher />
          <LanguageSwitcher />
          <SessionControls />
        </div>
      </header>
      <main className="sv-main">
        <Outlet />
      </main>
    </div>
  );
}

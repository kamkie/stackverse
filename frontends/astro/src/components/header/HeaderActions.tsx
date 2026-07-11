import { createSignal, For, onMount, Show } from "solid-js";
import { initializeClient } from "../../lib/initializeClient";
import { i18n, m, setLanguage, SUPPORTED_LANGUAGES } from "../../lib/i18n";
import { LOGIN_URL, logout, me, session } from "../../lib/session";
import {
  applyTheme,
  readStoredTheme,
  THEME_OPTIONS,
  type ThemeOption,
} from "../../lib/theme";

export default function HeaderActions() {
  const [theme, setTheme] = createSignal<ThemeOption>(readStoredTheme());

  onMount(() => {
    void initializeClient();
    if (import.meta.env.DEV) {
      void import("../../dev/forwardConsoleToDevServer").then(
        ({ forwardConsoleToDevServer }) => forwardConsoleToDevServer(),
      );
      void import("../../dev/logUserActions").then(({ logUserActions }) =>
        logUserActions(),
      );
    }
  });

  async function doLogout() {
    await logout();
    location.assign("/feed");
  }

  return (
    <div class="sv-header-actions">
      <div
        class="sv-theme-switch"
        role="group"
        aria-label={m(i18n(), "ui.theme.label")}
      >
        <For each={THEME_OPTIONS}>
          {(option) => (
            <button
              type="button"
              class={`sv-theme-option${theme() === option ? " is-active" : ""}`}
              onClick={() => {
                setTheme(option);
                applyTheme(option);
              }}
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
        when={session()?.authenticated}
        fallback={
          <a
            class="sv-button sv-button--primary sv-button--sm"
            href={LOGIN_URL}
          >
            {m(i18n(), "ui.action.login")}
          </a>
        }
      >
        <span class="sv-username">{me()?.username}</span>
        <button
          type="button"
          class="sv-button sv-button--ghost sv-button--sm"
          onClick={doLogout}
        >
          {m(i18n(), "ui.action.logout")}
        </button>
      </Show>
    </div>
  );
}

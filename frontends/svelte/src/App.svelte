<script lang="ts">
  import { onMount } from "svelte";
  import { fromStore } from "svelte/store";
  import ToastRegion, { type Toast } from "./components/ToastRegion.svelte";
  import {
    i18n,
    loadBundle,
    m,
    setLanguage,
    SUPPORTED_LANGUAGES,
  } from "./lib/i18n";
  import { goto, installRouteListener, route } from "./lib/route";
  import {
    LOGIN_URL,
    isAdmin,
    isModerator,
    logout,
    me,
    refreshSession,
    session,
  } from "./lib/session";
  import {
    applyTheme,
    readStoredTheme,
    THEME_OPTIONS,
    type ThemeOption,
  } from "./lib/theme";
  import MyBookmarksPage from "./pages/MyBookmarksPage.svelte";
  import MyReportsPage from "./pages/MyReportsPage.svelte";
  import PublicFeedPage from "./pages/PublicFeedPage.svelte";
  import AuditLogPage from "./pages/admin/AuditLogPage.svelte";
  import DashboardPage from "./pages/admin/DashboardPage.svelte";
  import MessagesPage from "./pages/admin/MessagesPage.svelte";
  import ReportsPage from "./pages/admin/ReportsPage.svelte";
  import UsersPage from "./pages/admin/UsersPage.svelte";

  let theme: ThemeOption = $state(readStoredTheme());
  let toasts: Toast[] = $state([]);
  let toastId = 0;
  const i18nState = fromStore(i18n);
  const routeState = fromStore(route);
  const sessionState = fromStore(session);
  const meState = fromStore(me);

  function navClass(path: string, exact = true): string {
    const currentRoute = routeState.current;
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
    theme = next;
    applyTheme(next);
  }

  function showToast(message: string, tone: "success" | "danger" = "success") {
    const toast = { id: ++toastId, message, tone };
    toasts = [...toasts, toast];
    window.setTimeout(() => {
      toasts = toasts.filter((item) => item.id !== toast.id);
    }, 3500);
  }

  async function doLogout() {
    await logout();
    goto("/feed");
  }

  onMount(() => {
    const uninstall = installRouteListener();
    void loadBundle();
    void refreshSession();
    return uninstall;
  });
</script>

{#if !i18nState.current.ready || sessionState.current === null}
  <div class="sv-loading"><span class="sv-spinner"></span></div>
{:else}
  <div class="sv-app">
    <header class="sv-header">
      <a href="/" class="sv-brand" onclick={navigateClick("/feed")}>
        {m(i18nState.current, "ui.app.title")}
      </a>
      <nav class="sv-nav">
        {#if sessionState.current.authenticated}
          <a
            href="/bookmarks"
            class={navClass("/bookmarks")}
            onclick={navigateClick("/bookmarks")}
          >
            {m(i18nState.current, "ui.nav.my-bookmarks")}
          </a>
          <a
            href="/reports"
            class={navClass("/reports")}
            onclick={navigateClick("/reports")}
          >
            {m(i18nState.current, "ui.nav.my-reports")}
          </a>
        {/if}
        <a
          href="/feed"
          class={navClass("/feed")}
          onclick={navigateClick("/feed")}
        >
          {m(i18nState.current, "ui.nav.public-feed")}
        </a>
        {#if isModerator(meState.current)}
          <a
            href="/admin"
            class={navClass("/admin", false)}
            onclick={navigateClick("/admin")}
          >
            {m(i18nState.current, "ui.nav.admin")}
          </a>
        {/if}
      </nav>
      <div class="sv-header-actions">
        <div
          class="sv-theme-switch"
          role="group"
          aria-label={m(i18nState.current, "ui.theme.label")}
        >
          {#each THEME_OPTIONS as option (option)}
            <button
              type="button"
              class={`sv-theme-option${theme === option ? " is-active" : ""}`}
              onclick={() => changeTheme(option)}
            >
              {m(i18nState.current, `ui.theme.${option}`)}
            </button>
          {/each}
        </div>
        <div class="sv-lang-switch" role="group" aria-label="language">
          {#each SUPPORTED_LANGUAGES as code (code)}
            <button
              type="button"
              lang={code}
              class={`sv-lang-option${(i18nState.current.lang ?? i18nState.current.resolvedLanguage) === code ? " is-active" : ""}`}
              onclick={() => setLanguage(code)}
            >
              {code.toUpperCase()}
            </button>
          {/each}
        </div>
        {#if sessionState.current.authenticated}
          <span class="sv-username">{sessionState.current.username}</span>
          <button
            type="button"
            class="sv-button sv-button--ghost sv-button--sm"
            onclick={doLogout}
          >
            {m(i18nState.current, "ui.action.logout")}
          </button>
        {:else}
          <a
            class="sv-button sv-button--primary sv-button--sm"
            href={LOGIN_URL}
          >
            {m(i18nState.current, "ui.action.login")}
          </a>
        {/if}
      </div>
    </header>

    <main class="sv-main">
      {#if routeState.current === "/bookmarks"}
        {#if sessionState.current.authenticated}
          <MyBookmarksPage toast={showToast} />
        {:else}
          <PublicFeedPage toast={showToast} />
        {/if}
      {:else if routeState.current === "/reports"}
        {#if sessionState.current.authenticated}
          <MyReportsPage toast={showToast} />
        {:else}
          <PublicFeedPage toast={showToast} />
        {/if}
      {:else if routeState.current.startsWith("/admin")}
        {#if !isModerator(meState.current)}
          <div class="sv-alert sv-alert--danger" role="alert">403</div>
        {:else}
          <div class="sv-layout">
            <aside class="sv-sidebar">
              <h2 class="sv-sidebar-title">
                {m(i18nState.current, "ui.nav.admin")}
              </h2>
              <nav class="sv-nav sv-nav--vertical" aria-label="Admin">
                <a
                  href="/admin"
                  class={navClass("/admin")}
                  onclick={navigateClick("/admin")}
                >
                  {m(i18nState.current, "ui.admin.dashboard")}
                </a>
                <a
                  href="/admin/reports"
                  class={navClass("/admin/reports")}
                  onclick={navigateClick("/admin/reports")}
                >
                  {m(i18nState.current, "ui.admin.reports")}
                </a>
                {#if isAdmin(meState.current)}
                  <a
                    href="/admin/users"
                    class={navClass("/admin/users")}
                    onclick={navigateClick("/admin/users")}
                  >
                    {m(i18nState.current, "ui.admin.users")}
                  </a>
                  <a
                    href="/admin/audit"
                    class={navClass("/admin/audit")}
                    onclick={navigateClick("/admin/audit")}
                  >
                    {m(i18nState.current, "ui.admin.audit")}
                  </a>
                  <a
                    href="/admin/messages"
                    class={navClass("/admin/messages")}
                    onclick={navigateClick("/admin/messages")}
                  >
                    {m(i18nState.current, "ui.admin.messages")}
                  </a>
                {/if}
              </nav>
            </aside>
            <section class="sv-content">
              {#if routeState.current === "/admin/reports"}
                <ReportsPage />
              {:else if routeState.current === "/admin/users" && isAdmin(meState.current)}
                <UsersPage />
              {:else if routeState.current === "/admin/audit" && isAdmin(meState.current)}
                <AuditLogPage />
              {:else if routeState.current === "/admin/messages" && isAdmin(meState.current)}
                <MessagesPage toast={showToast} />
              {:else if routeState.current === "/admin" || routeState.current === "/admin/"}
                <DashboardPage />
              {:else}
                <div class="sv-alert sv-alert--danger" role="alert">403</div>
              {/if}
            </section>
          </div>
        {/if}
      {:else}
        <PublicFeedPage toast={showToast} />
      {/if}
    </main>
    <ToastRegion {toasts} />
  </div>
{/if}

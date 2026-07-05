<script lang="ts">
  import { onMount } from "svelte";
  import ToastRegion, { type Toast } from "./components/ToastRegion.svelte";
  import { i18n, loadBundle, m, setLanguage, SUPPORTED_LANGUAGES } from "./lib/i18n";
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
  import { applyTheme, readStoredTheme, THEME_OPTIONS, type ThemeOption } from "./lib/theme";
  import MyBookmarksPage from "./pages/MyBookmarksPage.svelte";
  import MyReportsPage from "./pages/MyReportsPage.svelte";
  import PublicFeedPage from "./pages/PublicFeedPage.svelte";
  import AuditLogPage from "./pages/admin/AuditLogPage.svelte";
  import DashboardPage from "./pages/admin/DashboardPage.svelte";
  import MessagesPage from "./pages/admin/MessagesPage.svelte";
  import ReportsPage from "./pages/admin/ReportsPage.svelte";
  import UsersPage from "./pages/admin/UsersPage.svelte";

  let theme: ThemeOption = readStoredTheme();
  let toasts: Toast[] = [];
  let toastId = 0;

  function navClass(path: string, exact = true): string {
    const active = exact ? $route === path : $route === path || $route.startsWith(`${path}/`);
    return `sv-nav-link${active ? " is-active" : ""}`;
  }

  function navigate(path: string) {
    goto(path);
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

{#if !$i18n.ready || $session === null}
  <div class="sv-loading"><span class="sv-spinner"></span></div>
{:else}
  <div class="sv-app">
    <header class="sv-header">
      <a
        href="/"
        class="sv-brand"
        on:click|preventDefault={() => navigate("/feed")}
      >
        {m($i18n, "ui.app.title")}
      </a>
      <nav class="sv-nav">
        {#if $session.authenticated}
          <a
            href="/bookmarks"
            class={navClass("/bookmarks")}
            on:click|preventDefault={() => navigate("/bookmarks")}
          >
            {m($i18n, "ui.nav.my-bookmarks")}
          </a>
          <a
            href="/reports"
            class={navClass("/reports")}
            on:click|preventDefault={() => navigate("/reports")}
          >
            {m($i18n, "ui.nav.my-reports")}
          </a>
        {/if}
        <a
          href="/feed"
          class={navClass("/feed")}
          on:click|preventDefault={() => navigate("/feed")}
        >
          {m($i18n, "ui.nav.public-feed")}
        </a>
        {#if isModerator($me)}
          <a
            href="/admin"
            class={navClass("/admin", false)}
            on:click|preventDefault={() => navigate("/admin")}
          >
            {m($i18n, "ui.nav.admin")}
          </a>
        {/if}
      </nav>
      <div class="sv-header-actions">
        <div class="sv-theme-switch" role="group" aria-label={m($i18n, "ui.theme.label")}>
          {#each THEME_OPTIONS as option}
            <button
              type="button"
              class={`sv-theme-option${theme === option ? " is-active" : ""}`}
              on:click={() => changeTheme(option)}
            >
              {m($i18n, `ui.theme.${option}`)}
            </button>
          {/each}
        </div>
        <div class="sv-lang-switch" role="group" aria-label="language">
          {#each SUPPORTED_LANGUAGES as code}
            <button
              type="button"
              lang={code}
              class={`sv-lang-option${($i18n.lang ?? $i18n.resolvedLanguage) === code ? " is-active" : ""}`}
              on:click={() => setLanguage(code)}
            >
              {code.toUpperCase()}
            </button>
          {/each}
        </div>
        {#if $session.authenticated}
          <span class="sv-username">{$session.username}</span>
          <button type="button" class="sv-button sv-button--ghost sv-button--sm" on:click={doLogout}>
            {m($i18n, "ui.action.logout")}
          </button>
        {:else}
          <a class="sv-button sv-button--primary sv-button--sm" href={LOGIN_URL}>
            {m($i18n, "ui.action.login")}
          </a>
        {/if}
      </div>
    </header>

    <main class="sv-main">
      {#if $route === "/bookmarks"}
        {#if $session.authenticated}
          <MyBookmarksPage toast={showToast} />
        {:else}
          <PublicFeedPage toast={showToast} />
        {/if}
      {:else if $route === "/reports"}
        {#if $session.authenticated}
          <MyReportsPage toast={showToast} />
        {:else}
          <PublicFeedPage toast={showToast} />
        {/if}
      {:else if $route.startsWith("/admin")}
        {#if !isModerator($me)}
          <div class="sv-alert sv-alert--danger" role="alert">403</div>
        {:else}
          <div class="sv-layout">
            <aside class="sv-sidebar">
              <h2 class="sv-sidebar-title">{m($i18n, "ui.nav.admin")}</h2>
              <nav class="sv-nav sv-nav--vertical" aria-label="Admin">
                <a href="/admin" class={navClass("/admin")} on:click|preventDefault={() => navigate("/admin")}>
                  {m($i18n, "ui.admin.dashboard")}
                </a>
                <a href="/admin/reports" class={navClass("/admin/reports")} on:click|preventDefault={() => navigate("/admin/reports")}>
                  {m($i18n, "ui.admin.reports")}
                </a>
                {#if isAdmin($me)}
                  <a href="/admin/users" class={navClass("/admin/users")} on:click|preventDefault={() => navigate("/admin/users")}>
                    {m($i18n, "ui.admin.users")}
                  </a>
                  <a href="/admin/audit" class={navClass("/admin/audit")} on:click|preventDefault={() => navigate("/admin/audit")}>
                    {m($i18n, "ui.admin.audit")}
                  </a>
                  <a href="/admin/messages" class={navClass("/admin/messages")} on:click|preventDefault={() => navigate("/admin/messages")}>
                    {m($i18n, "ui.admin.messages")}
                  </a>
                {/if}
              </nav>
            </aside>
            <section class="sv-content">
              {#if $route === "/admin/reports"}
                <ReportsPage />
              {:else if $route === "/admin/users" && isAdmin($me)}
                <UsersPage />
              {:else if $route === "/admin/audit" && isAdmin($me)}
                <AuditLogPage />
              {:else if $route === "/admin/messages" && isAdmin($me)}
                <MessagesPage toast={showToast} />
              {:else if $route === "/admin" || $route === "/admin/"}
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

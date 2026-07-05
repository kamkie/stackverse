<script lang="ts">
  import { onMount } from "svelte";
  import { api } from "../../lib/api";
  import { goto } from "../../lib/route";
  import { i18n, m, mc } from "../../lib/i18n";
  import type { AdminStats } from "../../lib/types";

  let stats: AdminStats | null = null;
  let error: Error | null = null;

  onMount(() => {
    api<AdminStats>("/api/v1/admin/stats")
      .then((loaded) => (stats = loaded))
      .catch((caught) => (error = caught instanceof Error ? caught : new Error(String(caught))));
  });

  $: maxValue = Math.max(
    1,
    ...(stats?.daily.flatMap((day) => [day.bookmarksCreated, day.activeUsers]) ?? [1]),
  );
</script>

<h1 class="sv-page-title">{m($i18n, "ui.admin.dashboard")}</h1>

{#if error}
  <div class="sv-alert sv-alert--danger" role="alert">{error.message}</div>
{:else if !stats}
  <div class="sv-loading"><span class="sv-spinner"></span></div>
{:else}
  <div class="sv-stats-grid">
    <div class="sv-stat">
      <span class="sv-stat-value">{stats.totals.users}</span>
      <span class="sv-stat-label">{m($i18n, "ui.admin.stats.users")}</span>
    </div>
    <div class="sv-stat">
      <span class="sv-stat-value">{stats.totals.bookmarks}</span>
      <span class="sv-stat-label">{m($i18n, "ui.admin.stats.bookmarks")}</span>
    </div>
    <div class="sv-stat">
      <span class="sv-stat-value">{stats.totals.publicBookmarks}</span>
      <span class="sv-stat-label">{m($i18n, "ui.admin.stats.public-bookmarks")}</span>
    </div>
    <div class="sv-stat">
      <span class="sv-stat-value">{stats.totals.hiddenBookmarks}</span>
      <span class="sv-stat-label">{m($i18n, "ui.admin.stats.hidden-bookmarks")}</span>
    </div>
    <a
      class="sv-stat sv-stat--link"
      href="/admin/reports"
      on:click|preventDefault={() => goto("/admin/reports")}
    >
      <span class="sv-stat-value">{stats.totals.openReports}</span>
      <span class="sv-stat-label">{mc($i18n, "ui.admin.stats.open-reports", stats.totals.openReports)}</span>
    </a>
  </div>

  <div class="sv-card">
    <h2 class="sv-sidebar-title">{m($i18n, "ui.admin.chart.label")}</h2>
    <svg class="sv-chart" viewBox="0 0 620 180" role="img" aria-label={m($i18n, "ui.admin.chart.label")}>
      <line class="sv-chart-axis" x1="20" y1="150" x2="600" y2="150" />
      {#each stats.daily as day, index}
        {@const x = 24 + index * 19}
        {@const createdHeight = Math.max(1, (day.bookmarksCreated / maxValue) * 120)}
        {@const activeHeight = Math.max(1, (day.activeUsers / maxValue) * 120)}
        <rect class="sv-chart-bar" x={x} y={150 - createdHeight} width="7" height={createdHeight}>
          <title>{day.date}: {day.bookmarksCreated} {m($i18n, "ui.admin.stats.bookmarks-created")}</title>
        </rect>
        <rect class="sv-chart-bar sv-chart-bar--secondary" x={x + 8} y={150 - activeHeight} width="7" height={activeHeight}>
          <title>{day.date}: {day.activeUsers} {m($i18n, "ui.admin.stats.active-users")}</title>
        </rect>
      {/each}
    </svg>
    <div class="sv-legend">
      <span><span class="sv-legend-swatch"></span>{m($i18n, "ui.admin.stats.bookmarks-created")}</span>
      <span><span class="sv-legend-swatch sv-legend-swatch--secondary"></span>{m($i18n, "ui.admin.stats.active-users")}</span>
    </div>
  </div>
{/if}

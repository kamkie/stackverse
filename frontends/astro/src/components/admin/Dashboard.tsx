import { createMemo, createSignal, For, onMount, Show } from "solid-js";
import { api } from "../../lib/api";
import { i18n, m, mc } from "../../lib/i18n";
import type { AdminStats } from "../../lib/types";
import { useIsland } from "../../lib/island";
import { isModerator, me } from "../../lib/session";

export function DashboardContent() {
  const [stats, setStats] = createSignal<AdminStats | null>(null);
  const [error, setError] = createSignal<Error | null>(null);

  const maxValue = createMemo(() => {
    const currentStats = stats();
    if (!currentStats) return 1;
    return Math.max(
      1,
      ...currentStats.daily.flatMap((day) => [
        day.bookmarksCreated,
        day.activeUsers,
      ]),
    );
  });

  onMount(() => {
    api<AdminStats>("/api/v1/admin/stats")
      .then(setStats)
      .catch((caught) =>
        setError(caught instanceof Error ? caught : new Error(String(caught))),
      );
  });

  return (
    <>
      {error() ? (
        <div class="sv-alert sv-alert--danger" role="alert">
          {error()?.message}
        </div>
      ) : !stats() ? (
        <div class="sv-loading">
          <span class="sv-spinner" />
        </div>
      ) : (
        <>
          <div class="sv-stats-grid">
            <div class="sv-stat">
              <span class="sv-stat-value">{stats()!.totals.users}</span>
              <span class="sv-stat-label">
                {m(i18n(), "ui.admin.stats.users")}
              </span>
            </div>
            <div class="sv-stat">
              <span class="sv-stat-value">{stats()!.totals.bookmarks}</span>
              <span class="sv-stat-label">
                {m(i18n(), "ui.admin.stats.bookmarks")}
              </span>
            </div>
            <div class="sv-stat">
              <span class="sv-stat-value">
                {stats()!.totals.publicBookmarks}
              </span>
              <span class="sv-stat-label">
                {m(i18n(), "ui.admin.stats.public-bookmarks")}
              </span>
            </div>
            <div class="sv-stat">
              <span class="sv-stat-value">
                {stats()!.totals.hiddenBookmarks}
              </span>
              <span class="sv-stat-label">
                {m(i18n(), "ui.admin.stats.hidden-bookmarks")}
              </span>
            </div>
            <a class="sv-stat sv-stat--link" href="/admin/reports">
              <span class="sv-stat-value">{stats()!.totals.openReports}</span>
              <span class="sv-stat-label">
                {mc(
                  i18n(),
                  "ui.admin.stats.open-reports",
                  stats()!.totals.openReports,
                )}
              </span>
            </a>
          </div>

          <div class="sv-card">
            <h2 class="sv-sidebar-title">
              {m(i18n(), "ui.admin.chart.label")}
            </h2>
            <svg
              class="sv-chart"
              viewBox="0 0 620 180"
              role="img"
              aria-label={m(i18n(), "ui.admin.chart.label")}
            >
              <line class="sv-chart-axis" x1="20" y1="150" x2="600" y2="150" />
              <For each={stats()!.daily}>
                {(day, index) => {
                  const x = () => 24 + index() * 19;
                  const createdHeight = () =>
                    Math.max(1, (day.bookmarksCreated / maxValue()) * 120);
                  const activeHeight = () =>
                    Math.max(1, (day.activeUsers / maxValue()) * 120);
                  return (
                    <>
                      <rect
                        class="sv-chart-bar"
                        x={x()}
                        y={150 - createdHeight()}
                        width="7"
                        height={createdHeight()}
                      >
                        <title>
                          {day.date}: {day.bookmarksCreated}{" "}
                          {m(i18n(), "ui.admin.stats.bookmarks-created")}
                        </title>
                      </rect>
                      <rect
                        class="sv-chart-bar sv-chart-bar--secondary"
                        x={x() + 8}
                        y={150 - activeHeight()}
                        width="7"
                        height={activeHeight()}
                      >
                        <title>
                          {day.date}: {day.activeUsers}{" "}
                          {m(i18n(), "ui.admin.stats.active-users")}
                        </title>
                      </rect>
                    </>
                  );
                }}
              </For>
            </svg>
            <div class="sv-legend">
              <span>
                <span class="sv-legend-swatch" />
                {m(i18n(), "ui.admin.stats.bookmarks-created")}
              </span>
              <span>
                <span class="sv-legend-swatch sv-legend-swatch--secondary" />
                {m(i18n(), "ui.admin.stats.active-users")}
              </span>
            </div>
          </div>
        </>
      )}
    </>
  );
}

export default function Dashboard() {
  const island = useIsland();

  return (
    <Show
      when={island.ready()}
      fallback={
        <div class="sv-loading">
          <span class="sv-spinner" />
        </div>
      }
    >
      <Show
        when={isModerator(me())}
        fallback={
          <div class="sv-alert sv-alert--danger" role="alert">
            403
          </div>
        }
      >
        <DashboardContent />
      </Show>
    </Show>
  );
}

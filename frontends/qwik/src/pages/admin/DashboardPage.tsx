import {
  component$,
  useStore,
  useVisibleTask$,
  type PropFunction,
} from "@builder.io/qwik";
import { api } from "../../lib/api";
import { m, mc, type I18nState } from "../../lib/i18n";
import type { AdminStats } from "../../lib/types";

export default component$<{
  i18n: I18nState;
  onNavigate$: PropFunction<(path: string) => void>;
}>((props) => {
  const state = useStore<{ stats: AdminStats | null; error: string }>({
    stats: null,
    error: "",
  });

  useVisibleTask$(async () => {
    try {
      state.stats = await api<AdminStats>("/api/v1/admin/stats");
    } catch (caught) {
      state.error = caught instanceof Error ? caught.message : String(caught);
    }
  });

  const maxValue = state.stats
    ? Math.max(
        1,
        ...state.stats.daily.flatMap((day) => [
          day.bookmarksCreated,
          day.activeUsers,
        ]),
      )
    : 1;

  return (
    <>
      <h1 class="sv-page-title">{m(props.i18n, "ui.admin.dashboard")}</h1>

      {state.error ? (
        <div class="sv-alert sv-alert--danger" role="alert">
          {state.error}
        </div>
      ) : !state.stats ? (
        <div class="sv-loading">
          <span class="sv-spinner" />
        </div>
      ) : (
        <>
          <div class="sv-stats-grid">
            <div class="sv-stat">
              <span class="sv-stat-value">{state.stats.totals.users}</span>
              <span class="sv-stat-label">
                {m(props.i18n, "ui.admin.stats.users")}
              </span>
            </div>
            <div class="sv-stat">
              <span class="sv-stat-value">{state.stats.totals.bookmarks}</span>
              <span class="sv-stat-label">
                {m(props.i18n, "ui.admin.stats.bookmarks")}
              </span>
            </div>
            <div class="sv-stat">
              <span class="sv-stat-value">
                {state.stats.totals.publicBookmarks}
              </span>
              <span class="sv-stat-label">
                {m(props.i18n, "ui.admin.stats.public-bookmarks")}
              </span>
            </div>
            <div class="sv-stat">
              <span class="sv-stat-value">
                {state.stats.totals.hiddenBookmarks}
              </span>
              <span class="sv-stat-label">
                {m(props.i18n, "ui.admin.stats.hidden-bookmarks")}
              </span>
            </div>
            <a
              class="sv-stat sv-stat--link"
              href="/admin/reports"
              onClick$={(event: Event) => {
                event.preventDefault();
                void props.onNavigate$("/admin/reports");
              }}
            >
              <span class="sv-stat-value">
                {state.stats.totals.openReports}
              </span>
              <span class="sv-stat-label">
                {mc(
                  props.i18n,
                  "ui.admin.stats.open-reports",
                  state.stats.totals.openReports,
                )}
              </span>
            </a>
          </div>

          <div class="sv-card">
            <h2 class="sv-sidebar-title">
              {m(props.i18n, "ui.admin.chart.label")}
            </h2>
            <svg
              class="sv-chart"
              viewBox="0 0 620 180"
              role="img"
              aria-label={m(props.i18n, "ui.admin.chart.label")}
            >
              <line class="sv-chart-axis" x1="20" y1="150" x2="600" y2="150" />
              {state.stats.daily.map((day, index) => {
                const x = 24 + index * 19;
                const createdHeight = Math.max(
                  1,
                  (day.bookmarksCreated / maxValue) * 120,
                );
                const activeHeight = Math.max(
                  1,
                  (day.activeUsers / maxValue) * 120,
                );
                return (
                  <g key={day.date}>
                    <rect
                      class="sv-chart-bar"
                      x={String(x)}
                      y={String(150 - createdHeight)}
                      width="7"
                      height={String(createdHeight)}
                    >
                      <title>{`${day.date}: ${day.bookmarksCreated} ${m(props.i18n, "ui.admin.stats.bookmarks-created")}`}</title>
                    </rect>
                    <rect
                      class="sv-chart-bar sv-chart-bar--secondary"
                      x={String(x + 8)}
                      y={String(150 - activeHeight)}
                      width="7"
                      height={String(activeHeight)}
                    >
                      <title>{`${day.date}: ${day.activeUsers} ${m(props.i18n, "ui.admin.stats.active-users")}`}</title>
                    </rect>
                  </g>
                );
              })}
            </svg>
            <div class="sv-legend">
              <span>
                <span class="sv-legend-swatch" />
                {m(props.i18n, "ui.admin.stats.bookmarks-created")}
              </span>
              <span>
                <span class="sv-legend-swatch sv-legend-swatch--secondary" />
                {m(props.i18n, "ui.admin.stats.active-users")}
              </span>
            </div>
          </div>
        </>
      )}
    </>
  );
});

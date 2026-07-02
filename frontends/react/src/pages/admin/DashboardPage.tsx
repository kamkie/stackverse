import { Link } from "react-router";
import { ErrorState, Loading } from "../../components/states";
import { useI18n } from "../../i18n/I18nProvider";
import { useAdminStats, type AdminStats } from "./queries";

/** Grouped-bar SVG chart of the 30-day series, styled entirely by sv-chart classes. */
function DailyChart({ daily }: { daily: AdminStats["daily"] }) {
  const { t } = useI18n();
  const width = 620;
  const height = 160;
  const padding = { left: 24, bottom: 18, top: 6 };
  const chartHeight = height - padding.bottom - padding.top;
  const max = Math.max(1, ...daily.map((d) => Math.max(d.bookmarksCreated, d.activeUsers)));
  const slot = (width - padding.left) / Math.max(1, daily.length);
  const barWidth = Math.max(2, slot / 2 - 1.5);

  return (
    <svg
      className="sv-chart"
      viewBox={`0 0 ${width} ${height}`}
      role="img"
      aria-label={t("ui.admin.chart.label")}
    >
      {daily.map((day, i) => {
        const x = padding.left + i * slot;
        const created = (day.bookmarksCreated / max) * chartHeight;
        const active = (day.activeUsers / max) * chartHeight;
        return (
          <g key={day.date}>
            <title>
              {`${day.date}: ${day.bookmarksCreated} ${t("ui.admin.stats.bookmarks-created")}, ${day.activeUsers} ${t("ui.admin.stats.active-users")}`}
            </title>
            <rect
              className="sv-chart-bar"
              x={x}
              y={padding.top + chartHeight - created}
              width={barWidth}
              height={created}
            />
            <rect
              className="sv-chart-bar sv-chart-bar--secondary"
              x={x + barWidth + 1}
              y={padding.top + chartHeight - active}
              width={barWidth}
              height={active}
            />
          </g>
        );
      })}
      <line
        className="sv-chart-axis"
        x1={padding.left}
        y1={padding.top + chartHeight}
        x2={width}
        y2={padding.top + chartHeight}
      />
      <text className="sv-chart-label" x={0} y={padding.top + 10}>
        {max}
      </text>
      {daily[0] && (
        <text className="sv-chart-label" x={padding.left} y={height - 4}>
          {daily[0].date}
        </text>
      )}
      {daily.length > 1 && (
        <text
          className="sv-chart-label"
          x={width}
          y={height - 4}
          textAnchor="end"
        >
          {daily[daily.length - 1]?.date}
        </text>
      )}
    </svg>
  );
}

export function DashboardPage() {
  const { t, tCount } = useI18n();
  const stats = useAdminStats();

  if (stats.isPending) return <Loading />;
  if (stats.isError) return <ErrorState error={stats.error} />;

  const { totals, daily, topTags } = stats.data;
  const totalEntries = [
    ["ui.admin.stats.users", totals.users],
    ["ui.admin.stats.bookmarks", totals.bookmarks],
    ["ui.admin.stats.public-bookmarks", totals.publicBookmarks],
    ["ui.admin.stats.hidden-bookmarks", totals.hiddenBookmarks],
    ["ui.admin.stats.open-reports", totals.openReports],
  ] as const;

  return (
    <>
      <h1 className="sv-page-title">{t("ui.admin.dashboard")}</h1>
      <div className="sv-stats-grid">
        {totalEntries.map(([key, value]) =>
          // Open reports is the one stat with a queue behind it — link straight there.
          key === "ui.admin.stats.open-reports" ? (
            <Link to="/admin/reports" className="sv-stat sv-stat--link" key={key}>
              <span className="sv-stat-value">{value}</span>
              <span className="sv-stat-label">{tCount(key, value)}</span>
            </Link>
          ) : (
            <div className="sv-stat" key={key}>
              <span className="sv-stat-value">{value}</span>
              <span className="sv-stat-label">{t(key)}</span>
            </div>
          ),
        )}
      </div>
      <div className="sv-card">
        <div className="sv-legend">
          <span>
            <span className="sv-legend-swatch" />
            {t("ui.admin.stats.bookmarks-created")}
          </span>
          <span>
            <span className="sv-legend-swatch sv-legend-swatch--secondary" />
            {t("ui.admin.stats.active-users")}
          </span>
        </div>
        <DailyChart daily={daily} />
      </div>
      {topTags.length > 0 && (
        <div className="sv-card">
          <h2 className="sv-sidebar-title">{t("ui.nav.tags")}</h2>
          <ul className="sv-tag-list">
            {topTags.map(({ tag, count }) => (
              <li key={tag}>
                <span className="sv-tag">
                  {tag} <span className="sv-tag-count">{count}</span>
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </>
  );
}

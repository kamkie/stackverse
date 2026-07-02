import { useState } from "react";
import { Pagination } from "../../components/Pagination";
import { ErrorState, Loading } from "../../components/states";
import { useI18n } from "../../i18n/I18nProvider";
import { useReports, useResolveReport, type ReportStatus } from "./queries";

const STATUSES: ReportStatus[] = ["open", "dismissed", "actioned"];

/** Moderation queue: dismiss leaves the bookmark alone, action hides it. */
export function ReportsPage() {
  const { t, resolvedLanguage } = useI18n();
  const [status, setStatus] = useState<ReportStatus>("open");
  const [page, setPage] = useState(0);
  const reports = useReports(status, page);
  const resolve = useResolveReport();

  if (reports.isPending) return <Loading />;
  if (reports.isError) return <ErrorState error={reports.error} />;

  const { items, totalPages } = reports.data;

  return (
    <>
      <h1 className="sv-page-title">{t("ui.admin.reports")}</h1>
      <div className="sv-toolbar">
        <select
          className="sv-select"
          value={status}
          onChange={(e) => {
            setStatus(e.target.value as ReportStatus);
            setPage(0);
          }}
        >
          {STATUSES.map((s) => (
            <option key={s} value={s}>
              {t(`ui.report.status.${s}`)}
            </option>
          ))}
        </select>
      </div>
      {items.length === 0 ? (
        <div className="sv-empty">—</div>
      ) : (
        <div className="sv-table-wrap">
          <table className="sv-table">
            <thead>
              <tr>
                <th>{t("ui.field.created-at")}</th>
                <th>{t("ui.field.bookmark")}</th>
                <th>{t("ui.field.reporter")}</th>
                <th>{t("ui.field.reason")}</th>
                <th>{t("ui.field.comment")}</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {items.map((report) => (
                <tr key={report.id}>
                  <td>
                    <time dateTime={report.createdAt}>
                      {new Date(report.createdAt).toLocaleString(resolvedLanguage)}
                    </time>
                  </td>
                  <td className="sv-cell-mono">{report.bookmarkId.slice(0, 8)}</td>
                  <td>{report.reporter}</td>
                  <td>
                    <span className="sv-badge">
                      {t(`ui.report.reason.${report.reason}`)}
                    </span>
                  </td>
                  <td>{report.comment}</td>
                  <td className="sv-cell-actions">
                    {report.status === "open" ? (
                      <>
                        <button
                          type="button"
                          className="sv-button sv-button--sm"
                          disabled={resolve.isPending}
                          onClick={() =>
                            resolve.mutate({ id: report.id, resolution: "dismissed" })
                          }
                        >
                          {t("ui.action.dismiss")}
                        </button>{" "}
                        <button
                          type="button"
                          className="sv-button sv-button--danger sv-button--sm"
                          disabled={resolve.isPending}
                          onClick={() =>
                            resolve.mutate({ id: report.id, resolution: "actioned" })
                          }
                        >
                          {t("ui.action.action")}
                        </button>
                      </>
                    ) : (
                      <span
                        className={`sv-badge${report.status === "actioned" ? " sv-badge--danger" : ""}`}
                      >
                        {t(`ui.report.status.${report.status}`)}
                      </span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <Pagination page={page} totalPages={totalPages} onPage={setPage} />
    </>
  );
}

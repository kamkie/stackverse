import { useState } from "react";
import { Pagination } from "../../components/Pagination";
import { ErrorState, Loading } from "../../components/states";
import { useI18n } from "../../i18n/I18nProvider";
import { useBookmark, useReports, useResolveReport, type ReportStatus } from "./queries";

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
        <div className="sv-empty">{t("ui.reports.empty")}</div>
      ) : (
        <div className="sv-table-wrap">
          <table className="sv-table">
            <thead>
              <tr>
                <th scope="col">{t("ui.field.created-at")}</th>
                <th scope="col">{t("ui.field.bookmark")}</th>
                <th scope="col">{t("ui.field.reporter")}</th>
                <th scope="col">{t("ui.field.reason")}</th>
                <th scope="col">{t("ui.field.comment")}</th>
                <th scope="col">
                  <span className="sv-visually-hidden">{t("ui.field.actions")}</span>
                </th>
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
                  <td>
                    <BookmarkCell bookmarkId={report.bookmarkId} />
                  </td>
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

/**
 * Context for a reported bookmark. The read endpoint is owner-or-public-only,
 * so a `404` is an expected state (private, hidden, or already deleted —
 * moderators get no special access); those rows keep the raw id plus a hint.
 */
function BookmarkCell({ bookmarkId }: { bookmarkId: string }) {
  const { t } = useI18n();
  const bookmark = useBookmark(bookmarkId);

  if (bookmark.isSuccess) {
    return (
      <>
        <strong>{bookmark.data.title}</strong>
        <div>
          <a
            className="sv-bookmark-url"
            href={bookmark.data.url}
            target="_blank"
            rel="noreferrer"
          >
            {bookmark.data.url}
          </a>
        </div>
      </>
    );
  }
  return (
    <>
      <span className="sv-cell-mono">{bookmarkId}</span>
      {bookmark.isError && (
        <div className="sv-field-hint">{t("ui.reports.bookmark-unavailable")}</div>
      )}
    </>
  );
}

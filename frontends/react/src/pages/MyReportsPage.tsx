import { useState, type SubmitEvent } from "react";
import { ApiError, fieldErrorFor } from "../api/problem";
import { ConfirmDialog } from "../components/ConfirmDialog";
import { Dialog } from "../components/Dialog";
import { Field } from "../components/Field";
import { Pagination } from "../components/Pagination";
import { ErrorState, Loading } from "../components/states";
import { useToast } from "../components/ToastContext";
import { useI18n } from "../i18n/I18nContext";
import {
  useMyReports,
  useUpdateMyReport,
  useWithdrawReport,
  type Report,
  type ReportInput,
  type ReportStatus,
} from "../bookmarks/queries";
import { removeReportedId } from "../bookmarks/reportedStore";
import { useBookmark } from "./admin/queries";

const REASONS: ReportInput["reason"][] = ["spam", "offensive", "broken-link", "other"];
const STATUSES: ReportStatus[] = ["open", "dismissed", "actioned"];

function EditReportDialog({
  report,
  onClose,
}: {
  report: Report;
  onClose: () => void;
}) {
  const { t } = useI18n();
  const toast = useToast();
  const update = useUpdateMyReport();
  const [reason, setReason] = useState<ReportInput["reason"]>(report.reason);
  const [comment, setComment] = useState(report.comment ?? "");

  function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    update.mutate(
      { id: report.id, body: { reason, ...(comment ? { comment } : {}) } },
      {
        onSuccess: () => {
          toast.push(t("ui.toast.report-updated"));
          onClose();
        },
      },
    );
  }

  const error = update.error;
  const conflict = error instanceof ApiError && error.status === 409;

  return (
    <Dialog
      title={t("ui.my-reports.dialog.edit")}
      onClose={onClose}
      ctx={`report:${report.id}`}
    >
      <form className="sv-form" onSubmit={submit}>
        <Field label={t("ui.field.reason")} error={fieldErrorFor(error, "reason")}>
          <select
            className="sv-select"
            value={reason}
            onChange={(e) => setReason(e.target.value as ReportInput["reason"])}
          >
            {REASONS.map((r) => (
              <option key={r} value={r}>
                {t(`ui.report.reason.${r}`)}
              </option>
            ))}
          </select>
        </Field>
        <Field label={t("ui.field.comment")} error={fieldErrorFor(error, "comment")}>
          <textarea
            className="sv-textarea"
            value={comment}
            onChange={(e) => setComment(e.target.value)}
          />
        </Field>
        {conflict && (
          <div className="sv-alert sv-alert--warning" role="alert">
            {error.message}
          </div>
        )}
        <div className="sv-form-actions">
          <button type="button" className="sv-button" onClick={onClose}>
            {t("ui.action.cancel")}
          </button>
          <button
            type="submit"
            className="sv-button sv-button--primary"
            disabled={update.isPending}
          >
            {t("ui.action.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

/**
 * The caller's own reports (SPEC rule 13): status is moderation's answer, and
 * open reports can still be revised or withdrawn.
 */
export function MyReportsPage() {
  const { t, resolvedLanguage } = useI18n();
  const toast = useToast();
  const [status, setStatus] = useState<ReportStatus | "">("");
  const [page, setPage] = useState(0);
  const [editing, setEditing] = useState<Report | null>(null);
  const [withdrawing, setWithdrawing] = useState<Report | null>(null);
  const reports = useMyReports(status, page);
  const withdraw = useWithdrawReport();

  if (reports.isPending) return <Loading />;
  if (reports.isError) return <ErrorState error={reports.error} />;

  const { items, totalPages } = reports.data;

  return (
    <>
      <h1 className="sv-page-title">{t("ui.nav.my-reports")}</h1>
      <div className="sv-toolbar">
        <select
          className="sv-select"
          aria-label={t("ui.field.status")}
          value={status}
          onChange={(e) => {
            setStatus(e.target.value as ReportStatus | "");
            setPage(0);
          }}
        >
          <option value="">{t("ui.my-reports.filter.all-statuses")}</option>
          {STATUSES.map((s) => (
            <option key={s} value={s}>
              {t(`ui.report.status.${s}`)}
            </option>
          ))}
        </select>
      </div>
      {items.length === 0 ? (
        <div className="sv-empty">{t("ui.my-reports.empty")}</div>
      ) : (
        <div className="sv-table-wrap">
          <table className="sv-table">
            <thead>
              <tr>
                <th scope="col">{t("ui.field.created-at")}</th>
                <th scope="col">{t("ui.field.bookmark")}</th>
                <th scope="col">{t("ui.field.reason")}</th>
                <th scope="col">{t("ui.field.comment")}</th>
                <th scope="col">{t("ui.field.status")}</th>
                <th scope="col">
                  <span className="sv-visually-hidden">{t("ui.field.actions")}</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {items.map((report) => (
                <tr key={report.id} data-ctx={`report:${report.id}`}>
                  <td>
                    <time dateTime={report.createdAt}>
                      {new Date(report.createdAt).toLocaleString(resolvedLanguage)}
                    </time>
                  </td>
                  <td>
                    <BookmarkCell bookmarkId={report.bookmarkId} />
                  </td>
                  <td>
                    <span className="sv-badge">
                      {t(`ui.report.reason.${report.reason}`)}
                    </span>
                  </td>
                  <td>{report.comment}</td>
                  <td>
                    <span
                      className={`sv-badge${report.status === "actioned" ? " sv-badge--danger" : ""}`}
                    >
                      {t(`ui.report.status.${report.status}`)}
                    </span>
                    {report.resolutionNote && (
                      <div className="sv-field-hint">{report.resolutionNote}</div>
                    )}
                  </td>
                  <td className="sv-cell-actions">
                    {report.status === "open" && (
                      <>
                        <button
                          type="button"
                          className="sv-button sv-button--ghost sv-button--sm"
                          onClick={() => setEditing(report)}
                        >
                          {t("ui.action.edit")}
                        </button>{" "}
                        <button
                          type="button"
                          className="sv-button sv-button--ghost sv-button--sm"
                          onClick={() => setWithdrawing(report)}
                        >
                          {t("ui.action.withdraw")}
                        </button>
                      </>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <Pagination page={page} totalPages={totalPages} onPage={setPage} />
      {editing && (
        <EditReportDialog report={editing} onClose={() => setEditing(null)} />
      )}
      {withdrawing && (
        <ConfirmDialog
          title={t("ui.action.withdraw")}
          body={t("ui.confirm.withdraw-report")}
          ctx={`report:${withdrawing.id}`}
          confirmLabel={t("ui.action.withdraw")}
          cancelLabel={t("ui.action.cancel")}
          pending={withdraw.isPending}
          onConfirm={() =>
            withdraw.mutate(withdrawing.id, {
              onSuccess: () => {
                // withdrawal frees the slot (SPEC rule 13) — the feed's
                // session-local "Reported" marker must not outlive the report
                removeReportedId(withdrawing.bookmarkId);
                setWithdrawing(null);
                toast.push(t("ui.toast.report-withdrawn"));
              },
              onError: (error) =>
                toast.push(
                  error instanceof Error ? error.message : String(error),
                  "danger",
                ),
            })
          }
          onClose={() => setWithdrawing(null)}
        />
      )}
    </>
  );
}

/**
 * Context for a reported bookmark. The read endpoint is owner-or-public-only,
 * so a `404` is an expected state — a bookmark that was hidden after the
 * report (or deleted) keeps the raw id plus a hint.
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

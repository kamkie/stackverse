import { useState, type SubmitEvent } from "react";
import { ApiError, fieldErrorFor } from "../api/problem";
import { Dialog } from "../components/Dialog";
import { Field } from "../components/Field";
import { useToast } from "../components/Toast";
import { useI18n } from "../i18n/I18nProvider";
import { useReportBookmark, type Bookmark, type ReportInput } from "./queries";

const REASONS: ReportInput["reason"][] = ["spam", "offensive", "broken-link", "other"];

export function ReportDialog({
  bookmark,
  onClose,
  onReported,
}: {
  bookmark: Bookmark;
  onClose: () => void;
  /**
   * Fires when a submit confirms the reported state — a 201 create or a 409
   * duplicate (SPEC rule 13: proof an open report already exists) — so the
   * caller can mark the card as reported.
   */
  onReported?: ((bookmarkId: string) => void) | undefined;
}) {
  const { t } = useI18n();
  const toast = useToast();
  const report = useReportBookmark();
  const [reason, setReason] = useState<ReportInput["reason"]>("spam");
  const [comment, setComment] = useState("");

  function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    report.mutate(
      {
        id: bookmark.id,
        body: { reason, ...(comment ? { comment } : {}) },
      },
      {
        onSuccess: () => {
          toast.push(t("ui.toast.report-submitted"), "success");
          onReported?.(bookmark.id);
          onClose();
        },
        onError: (error) => {
          // A 409 means this user already has an open report on the bookmark
          // (SPEC rule 13) — positive proof of the reported state, so confirm
          // instead of surfacing an error.
          if (error instanceof ApiError && error.status === 409) {
            toast.push(t("ui.toast.report-duplicate"), "success");
            onReported?.(bookmark.id);
            onClose();
          }
        },
      },
    );
  }

  const error = report.error;

  return (
    <Dialog
      title={`${t("ui.action.report")} — ${bookmark.title}`}
      onClose={onClose}
      ctx={`bookmark:${bookmark.id}`}
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
        <div className="sv-form-actions">
          <button type="button" className="sv-button" onClick={onClose}>
            {t("ui.action.cancel")}
          </button>
          <button
            type="submit"
            className="sv-button sv-button--primary"
            disabled={report.isPending}
          >
            {t("ui.action.report")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

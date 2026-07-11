import { createSignal, For } from "solid-js";
import { ApiError, api, fieldErrorFor, jsonBody } from "../lib/api";
import { i18n, m } from "../lib/i18n";
import { markReported } from "../lib/reportedStore";
import type { Bookmark, Report, ReportInput, ReportReason } from "../lib/types";
import { REPORT_REASONS } from "../lib/types";
import Dialog from "./Dialog";
import Field from "./Field";

interface Props {
  bookmark: Bookmark;
  toast: (message: string, tone?: "success" | "danger") => void;
  onDone: () => void | Promise<void>;
  onClose: () => void;
}

export default function ReportDialog(props: Props) {
  const [reason, setReason] = createSignal<ReportReason>(REPORT_REASONS[0]);
  const [comment, setComment] = createSignal("");
  const [error, setError] = createSignal<unknown>(undefined);
  const [pending, setPending] = createSignal(false);

  async function submit(event: SubmitEvent) {
    event.preventDefault();
    setPending(true);
    setError(undefined);
    const body: ReportInput = { reason: reason(), ...(comment() ? { comment: comment() } : {}) };
    try {
      await api<Report>(`/api/v1/bookmarks/${props.bookmark.id}/reports`, {
        method: "POST",
        ...jsonBody(body),
      });
      markReported(props.bookmark.id);
      props.toast(m(i18n(), "ui.toast.report-submitted"));
      await props.onDone();
      props.onClose();
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 409) {
        markReported(props.bookmark.id);
        props.toast(m(i18n(), "ui.toast.report-duplicate"));
        await props.onDone();
        props.onClose();
        return;
      }
      setError(caught);
    } finally {
      setPending(false);
    }
  }

  return (
    <Dialog title={m(i18n(), "ui.action.report")} ctx={`bookmark:${props.bookmark.id}`} onClose={props.onClose}>
      <form class="sv-form" onSubmit={submit}>
        <Field label={m(i18n(), "ui.field.reason")} error={fieldErrorFor(error(), "reason")}>
          <select
            class="sv-select"
            value={reason()}
            onChange={(event) => setReason(event.currentTarget.value as ReportReason)}
          >
            <For each={REPORT_REASONS}>
              {(option) => <option value={option}>{m(i18n(), `ui.report.reason.${option}`)}</option>}
            </For>
          </select>
        </Field>
        <Field label={m(i18n(), "ui.field.comment")} error={fieldErrorFor(error(), "comment")}>
          <textarea class="sv-textarea" value={comment()} onInput={(event) => setComment(event.currentTarget.value)} />
        </Field>
        <div class="sv-form-actions">
          <button type="button" class="sv-button" onClick={props.onClose}>
            {m(i18n(), "ui.action.cancel")}
          </button>
          <button type="submit" class="sv-button sv-button--primary" disabled={pending()}>
            {m(i18n(), "ui.action.report")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

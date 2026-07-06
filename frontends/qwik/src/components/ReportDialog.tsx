import { component$, useSignal, type PropFunction } from "@builder.io/qwik";
import { api, apiStatus, fieldErrorFor, jsonBody } from "../lib/api";
import { formText } from "../lib/form";
import { m, type I18nState } from "../lib/i18n";
import { markReported } from "../lib/reportedStore";
import type { Bookmark, Report, ReportInput, ReportReason } from "../lib/types";
import { REPORT_REASONS } from "../lib/types";
import Dialog from "./Dialog";
import Field from "./Field";

interface Props {
  i18n: I18nState;
  bookmark: Bookmark;
  toast$: PropFunction<(message: string, tone?: "success" | "danger") => void>;
  onDone$: PropFunction<() => void | Promise<void>>;
  onClose$: PropFunction<() => void>;
}

export default component$<Props>((props) => {
  const reason = useSignal<ReportReason>(REPORT_REASONS[0]);
  const comment = useSignal("");
  const error = useSignal<unknown>(undefined);
  const pending = useSignal(false);

  return (
    <Dialog title={m(props.i18n, "ui.action.report")} ctx={`bookmark:${props.bookmark.id}`} onClose$={props.onClose$}>
      <form
        class="sv-form"
        preventdefault:submit
        onSubmit$={async (event: Event) => {
          pending.value = true;
          error.value = undefined;
          const form = event.target as HTMLFormElement;
          reason.value = formText(form, "reason") as ReportReason;
          comment.value = formText(form, "comment");
          const body: ReportInput = {
            reason: reason.value,
            ...(comment.value ? { comment: comment.value } : {}),
          };
          try {
            await api<Report>(`/api/v1/bookmarks/${props.bookmark.id}/reports`, {
              method: "POST",
              ...jsonBody(body),
            });
            markReported(props.bookmark.id);
            await props.toast$(m(props.i18n, "ui.toast.report-submitted"));
            await props.onDone$();
            await props.onClose$();
          } catch (caught) {
            if (apiStatus(caught) === 409) {
              markReported(props.bookmark.id);
              await props.toast$(m(props.i18n, "ui.toast.report-duplicate"));
              await props.onDone$();
              await props.onClose$();
              return;
            }
            error.value = caught;
          } finally {
            pending.value = false;
          }
        }}
      >
        <Field label={m(props.i18n, "ui.field.reason")} error={fieldErrorFor(error.value, "reason")}>
          <select
            name="reason"
            class="sv-select"
            value={reason.value}
            onChange$={(event: Event) => (reason.value = (event.target as HTMLInputElement).value as ReportReason)}
          >
            {REPORT_REASONS.map((option) => (
              <option key={option} value={option}>
                {m(props.i18n, `ui.report.reason.${option}`)}
              </option>
            ))}
          </select>
        </Field>
        <Field label={m(props.i18n, "ui.field.comment")} error={fieldErrorFor(error.value, "comment")}>
          <textarea name="comment" class="sv-textarea" value={comment.value} onInput$={(event: Event) => (comment.value = (event.target as HTMLInputElement).value)} />
        </Field>
        <div class="sv-form-actions">
          <button type="button" class="sv-button" onClick$={props.onClose$}>
            {m(props.i18n, "ui.action.cancel")}
          </button>
          <button type="submit" class="sv-button sv-button--primary" disabled={pending.value}>
            {m(props.i18n, "ui.action.report")}
          </button>
        </div>
      </form>
    </Dialog>
  );
});

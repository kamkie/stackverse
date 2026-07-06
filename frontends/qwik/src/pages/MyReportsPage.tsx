import { $, component$, useSignal, useStore, useVisibleTask$, type PropFunction } from "@builder.io/qwik";
import BookmarkContext from "../components/BookmarkContext";
import ConfirmDialog from "../components/ConfirmDialog";
import Dialog from "../components/Dialog";
import Field from "../components/Field";
import Pagination from "../components/Pagination";
import { api, apiMessage, apiStatus, fieldErrorFor, jsonBody, queryString } from "../lib/api";
import { formatDate } from "../lib/format";
import { formText } from "../lib/form";
import { m, type I18nState } from "../lib/i18n";
import { removeReported } from "../lib/reportedStore";
import type { Page, Report, ReportInput, ReportReason, ReportStatus } from "../lib/types";
import { REPORT_REASONS, REPORT_STATUSES } from "../lib/types";

interface Props {
  i18n: I18nState;
  toast$: PropFunction<(message: string, tone?: "success" | "danger") => void>;
}

export default component$<Props>((props) => {
  const state = useStore<{
    status: ReportStatus | "";
    page: number;
    reports: Page<Report> | null;
    loading: boolean;
    error: string;
    editing: Report | null;
    withdrawing: Report | null;
  }>({
    status: "",
    page: 0,
    reports: null,
    loading: true,
    error: "",
    editing: null,
    withdrawing: null,
  });
  const editReason = useSignal<ReportReason>(REPORT_REASONS[0]);
  const editComment = useSignal("");
  const editError = useSignal<unknown>(undefined);
  const editPending = useSignal(false);

  const load$ = $(async () => {
    state.loading = true;
    state.error = "";
    try {
      let nextReports = await api<Page<Report>>(`/api/v1/reports${queryString({ status: state.status, page: state.page })}`);
      if (nextReports.items.length === 0 && state.page > 0) {
        state.page = Math.max(0, nextReports.totalPages - 1);
        nextReports = await api<Page<Report>>(`/api/v1/reports${queryString({ status: state.status, page: state.page })}`);
      }
      state.reports = nextReports;
    } catch (caught) {
      state.error = caught instanceof Error ? caught.message : String(caught);
    } finally {
      state.loading = false;
    }
  });

  useVisibleTask$(() => {
    void load$();
  });

  return (
    <>
      <section class="sv-content">
        <h1 class="sv-page-title">{m(props.i18n, "ui.nav.my-reports")}</h1>
        <div class="sv-toolbar">
          <select
            class="sv-select"
            aria-label={m(props.i18n, "ui.field.status")}
            value={state.status}
            onChange$={(event: Event) => {
              state.status = (event.target as HTMLInputElement).value as ReportStatus | "";
              state.page = 0;
              void load$();
            }}
          >
            <option value="">{m(props.i18n, "ui.my-reports.filter.all-statuses")}</option>
            {REPORT_STATUSES.map((option) => (
              <option key={option} value={option}>{m(props.i18n, `ui.report.status.${option}`)}</option>
            ))}
          </select>
        </div>

        {state.loading && !state.reports ? (
          <div class="sv-loading"><span class="sv-spinner" /></div>
        ) : state.error ? (
          <div class="sv-alert sv-alert--danger" role="alert">{state.error}</div>
        ) : !state.reports || state.reports.items.length === 0 ? (
          <div class="sv-empty">{m(props.i18n, "ui.my-reports.empty")}</div>
        ) : (
          <>
            <div class="sv-table-wrap">
              <table class="sv-table">
                <thead>
                  <tr>
                    <th scope="col">{m(props.i18n, "ui.field.created-at")}</th>
                    <th scope="col">{m(props.i18n, "ui.field.bookmark")}</th>
                    <th scope="col">{m(props.i18n, "ui.field.reason")}</th>
                    <th scope="col">{m(props.i18n, "ui.field.comment")}</th>
                    <th scope="col">{m(props.i18n, "ui.field.status")}</th>
                    <th scope="col"><span class="sv-visually-hidden">{m(props.i18n, "ui.field.actions")}</span></th>
                  </tr>
                </thead>
                <tbody>
                  {state.reports.items.map((report) => (
                    <tr key={`${report.id}:${report.status}:${report.reason}:${report.comment ?? ""}:${report.resolutionNote ?? ""}`} data-ctx={`report:${report.id}`}>
                      <td><time dateTime={report.createdAt}>{formatDate(report.createdAt, props.i18n.resolvedLanguage)}</time></td>
                      <td><BookmarkContext i18n={props.i18n} bookmarkId={report.bookmarkId} /></td>
                      <td><span class="sv-badge">{m(props.i18n, `ui.report.reason.${report.reason}`)}</span></td>
                      <td>{report.comment}</td>
                      <td>
                        <span class={`sv-badge${report.status === "actioned" ? " sv-badge--danger" : ""}`}>
                          {m(props.i18n, `ui.report.status.${report.status}`)}
                        </span>
                        {report.resolutionNote ? <div class="sv-field-hint">{report.resolutionNote}</div> : null}
                      </td>
                      <td class="sv-cell-actions">
                        {report.status === "open" ? (
                          <>
                            <button
                              type="button"
                              class="sv-button sv-button--ghost sv-button--sm"
                              onClick$={() => {
                                state.editing = report;
                                editReason.value = report.reason;
                                editComment.value = report.comment ?? "";
                                editError.value = undefined;
                              }}
                            >
                              {m(props.i18n, "ui.action.edit")}
                            </button>
                            <button
                              type="button"
                              class="sv-button sv-button--ghost sv-button--sm"
                              onClick$={() => (state.withdrawing = report)}
                            >
                              {m(props.i18n, "ui.action.withdraw")}
                            </button>
                          </>
                        ) : null}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pagination
              i18n={props.i18n}
              page={state.page}
              totalPages={state.reports.totalPages}
              onPage$={(next) => {
                state.page = next;
                void load$();
              }}
            />
          </>
        )}
      </section>

      {state.editing ? (
        <Dialog title={m(props.i18n, "ui.my-reports.dialog.edit")} ctx={`report:${state.editing.id}`} onClose$={() => (state.editing = null)}>
          <form
            class="sv-form"
            preventdefault:submit
            onSubmit$={async (event: Event) => {
              if (!state.editing) return;
              editPending.value = true;
              editError.value = undefined;
              const form = event.target as HTMLFormElement;
              editReason.value = formText(form, "reason") as ReportReason;
              editComment.value = formText(form, "comment");
              try {
                const body: ReportInput = {
                  reason: editReason.value,
                  ...(editComment.value ? { comment: editComment.value } : {}),
                };
                const updatedReport = await api<Report>(`/api/v1/reports/${state.editing.id}`, {
                  method: "PUT",
                  ...jsonBody(body),
                });
                if (state.reports) {
                  state.reports = {
                    ...state.reports,
                    items: state.reports.items.map((report) => (report.id === updatedReport.id ? updatedReport : report)),
                  };
                }
                state.editing = null;
                await props.toast$(m(props.i18n, "ui.toast.report-updated"));
              } catch (caught) {
                editError.value = caught;
              } finally {
                editPending.value = false;
              }
            }}
          >
            <Field label={m(props.i18n, "ui.field.reason")} error={fieldErrorFor(editError.value, "reason")}>
              <select name="reason" class="sv-select" value={editReason.value} onChange$={(event: Event) => (editReason.value = (event.target as HTMLInputElement).value as ReportReason)}>
                {REPORT_REASONS.map((option) => (
                  <option key={option} value={option}>{m(props.i18n, `ui.report.reason.${option}`)}</option>
                ))}
              </select>
            </Field>
            <Field label={m(props.i18n, "ui.field.comment")} error={fieldErrorFor(editError.value, "comment")}>
              <textarea name="comment" class="sv-textarea" value={editComment.value} onInput$={(event: Event) => (editComment.value = (event.target as HTMLInputElement).value)} />
            </Field>
            {apiStatus(editError.value) === 409 ? (
              <div class="sv-alert sv-alert--warning" role="alert">{apiMessage(editError.value)}</div>
            ) : null}
            <div class="sv-form-actions">
              <button type="button" class="sv-button" onClick$={() => (state.editing = null)}>
                {m(props.i18n, "ui.action.cancel")}
              </button>
              <button type="submit" class="sv-button sv-button--primary" disabled={editPending.value}>
                {m(props.i18n, "ui.action.save")}
              </button>
            </div>
          </form>
        </Dialog>
      ) : null}

      {state.withdrawing ? (
        <ConfirmDialog
          title={m(props.i18n, "ui.action.withdraw")}
          body={m(props.i18n, "ui.confirm.withdraw-report")}
          ctx={`report:${state.withdrawing.id}`}
          confirmLabel={m(props.i18n, "ui.action.withdraw")}
          cancelLabel={m(props.i18n, "ui.action.cancel")}
          onConfirm$={async () => {
            if (!state.withdrawing) return;
            try {
              await api<void>(`/api/v1/reports/${state.withdrawing.id}`, { method: "DELETE" });
              removeReported(state.withdrawing.bookmarkId);
              state.withdrawing = null;
              await props.toast$(m(props.i18n, "ui.toast.report-withdrawn"));
              await load$();
            } catch (caught) {
              await props.toast$(caught instanceof Error ? caught.message : String(caught), "danger");
            }
          }}
          onClose$={() => (state.withdrawing = null)}
        />
      ) : null}
    </>
  );
});

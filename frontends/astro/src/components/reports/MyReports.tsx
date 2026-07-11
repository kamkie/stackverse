import { createSignal, For, onMount, Show } from "solid-js";
import BookmarkContext from "../BookmarkContext";
import ConfirmDialog from "../ConfirmDialog";
import Dialog from "../Dialog";
import Field from "../Field";
import Pagination from "../Pagination";
import { ApiError, api, fieldErrorFor, jsonBody, queryString } from "../../lib/api";
import { formatDate } from "../../lib/format";
import { i18n, m } from "../../lib/i18n";
import { removeReported } from "../../lib/reportedStore";
import type { Page, Report, ReportInput, ReportReason, ReportStatus } from "../../lib/types";
import { REPORT_REASONS, REPORT_STATUSES } from "../../lib/types";
import ClientPage from "../ClientPage";
import { PublicFeedContent } from "../bookmarks/PublicFeed";

interface Props {
  toast: (message: string, tone?: "success" | "danger") => void;
}

export function MyReportsContent(props: Props) {
  const [status, setStatus] = createSignal<ReportStatus | "">("");
  const [page, setPage] = createSignal(0);
  const [reports, setReports] = createSignal<Page<Report> | null>(null);
  const [loading, setLoading] = createSignal(true);
  const [error, setError] = createSignal<Error | null>(null);
  const [editing, setEditing] = createSignal<Report | null>(null);
  const [withdrawing, setWithdrawing] = createSignal<Report | null>(null);
  const [editReason, setEditReason] = createSignal<ReportReason>(REPORT_REASONS[0]);
  const [editComment, setEditComment] = createSignal("");
  const [editError, setEditError] = createSignal<unknown>(undefined);
  const [editPending, setEditPending] = createSignal(false);
  let loadRequest = 0;

  async function load() {
    const request = ++loadRequest;
    setLoading(true);
    setError(null);
    try {
      const nextReports = await api<Page<Report>>(`/api/v1/reports${queryString({ status: status(), page: page() })}`);
      if (request !== loadRequest) return;
      if (nextReports.items.length === 0 && page() > 0) {
        setPage(Math.max(0, nextReports.totalPages - 1));
        await load();
        return;
      }
      setReports(nextReports);
    } catch (caught) {
      if (request === loadRequest) {
        setError(caught instanceof Error ? caught : new Error(String(caught)));
      }
    } finally {
      if (request === loadRequest) setLoading(false);
    }
  }

  function openEdit(report: Report) {
    setEditing(report);
    setEditReason(report.reason);
    setEditComment(report.comment ?? "");
    setEditError(undefined);
  }

  async function saveEdit(event: SubmitEvent) {
    event.preventDefault();
    const report = editing();
    if (!report) return;
    setEditPending(true);
    setEditError(undefined);
    try {
      const body: ReportInput = {
        reason: editReason(),
        ...(editComment() ? { comment: editComment() } : {}),
      };
      await api<Report>(`/api/v1/reports/${report.id}`, {
        method: "PUT",
        ...jsonBody(body),
      });
      setEditing(null);
      props.toast(m(i18n(), "ui.toast.report-updated"));
      await load();
    } catch (caught) {
      setEditError(caught);
    } finally {
      setEditPending(false);
    }
  }

  async function withdraw(report: Report) {
    try {
      await api<void>(`/api/v1/reports/${report.id}`, { method: "DELETE" });
      removeReported(report.bookmarkId);
      setWithdrawing(null);
      props.toast(m(i18n(), "ui.toast.report-withdrawn"));
      await load();
    } catch (caught) {
      props.toast(caught instanceof Error ? caught.message : String(caught), "danger");
    }
  }

  onMount(() => {
    void load();
  });

  return (
    <>
      <section class="sv-content">
        <h1 class="sv-page-title">{m(i18n(), "ui.nav.my-reports")}</h1>
        <div class="sv-toolbar">
          <select
            class="sv-select"
            aria-label={m(i18n(), "ui.field.status")}
            value={status()}
            onChange={(event) => {
              setStatus(event.currentTarget.value as ReportStatus | "");
              setPage(0);
              void load();
            }}
          >
            <option value="">{m(i18n(), "ui.my-reports.filter.all-statuses")}</option>
            <For each={REPORT_STATUSES}>
              {(option) => <option value={option}>{m(i18n(), `ui.report.status.${option}`)}</option>}
            </For>
          </select>
        </div>

        {loading() && !reports() ? (
          <div class="sv-loading"><span class="sv-spinner" /></div>
        ) : error() ? (
          <div class="sv-alert sv-alert--danger" role="alert">{error()?.message}</div>
        ) : !reports() || reports()!.items.length === 0 ? (
          <div class="sv-empty">{m(i18n(), "ui.my-reports.empty")}</div>
        ) : (
          <>
            <div class="sv-table-wrap">
              <table class="sv-table">
                <thead>
                  <tr>
                    <th scope="col">{m(i18n(), "ui.field.created-at")}</th>
                    <th scope="col">{m(i18n(), "ui.field.bookmark")}</th>
                    <th scope="col">{m(i18n(), "ui.field.reason")}</th>
                    <th scope="col">{m(i18n(), "ui.field.comment")}</th>
                    <th scope="col">{m(i18n(), "ui.field.status")}</th>
                    <th scope="col"><span class="sv-visually-hidden">{m(i18n(), "ui.field.actions")}</span></th>
                  </tr>
                </thead>
                <tbody>
                  <For each={reports()!.items}>
                    {(report) => (
                      <tr data-ctx={`report:${report.id}`}>
                        <td><time dateTime={report.createdAt}>{formatDate(report.createdAt, i18n().resolvedLanguage)}</time></td>
                        <td><BookmarkContext bookmarkId={report.bookmarkId} /></td>
                        <td><span class="sv-badge">{m(i18n(), `ui.report.reason.${report.reason}`)}</span></td>
                        <td>{report.comment}</td>
                        <td>
                          <span class={`sv-badge${report.status === "actioned" ? " sv-badge--danger" : ""}`}>
                            {m(i18n(), `ui.report.status.${report.status}`)}
                          </span>
                          <Show when={report.resolutionNote}>
                            {(note) => <div class="sv-field-hint">{note()}</div>}
                          </Show>
                        </td>
                        <td class="sv-cell-actions">
                          <Show when={report.status === "open"}>
                            <button type="button" class="sv-button sv-button--ghost sv-button--sm" onClick={() => openEdit(report)}>
                              {m(i18n(), "ui.action.edit")}
                            </button>
                            <button type="button" class="sv-button sv-button--ghost sv-button--sm" onClick={() => setWithdrawing(report)}>
                              {m(i18n(), "ui.action.withdraw")}
                            </button>
                          </Show>
                        </td>
                      </tr>
                    )}
                  </For>
                </tbody>
              </table>
            </div>
            <Pagination page={page()} totalPages={reports()!.totalPages} onPage={(next) => {
              setPage(next);
              void load();
            }} />
          </>
        )}
      </section>

      <Show when={editing()}>
        {(report) => (
          <Dialog title={m(i18n(), "ui.my-reports.dialog.edit")} ctx={`report:${report().id}`} onClose={() => setEditing(null)}>
            <form class="sv-form" onSubmit={saveEdit}>
              <Field label={m(i18n(), "ui.field.reason")} error={fieldErrorFor(editError(), "reason")}>
                <select class="sv-select" value={editReason()} onChange={(event) => setEditReason(event.currentTarget.value as ReportReason)}>
                  <For each={REPORT_REASONS}>
                    {(option) => <option value={option}>{m(i18n(), `ui.report.reason.${option}`)}</option>}
                  </For>
                </select>
              </Field>
              <Field label={m(i18n(), "ui.field.comment")} error={fieldErrorFor(editError(), "comment")}>
                <textarea class="sv-textarea" value={editComment()} onInput={(event) => setEditComment(event.currentTarget.value)} />
              </Field>
              <Show when={editError() instanceof ApiError && (editError() as ApiError).status === 409}>
                <div class="sv-alert sv-alert--warning" role="alert">{(editError() as ApiError).message}</div>
              </Show>
              <div class="sv-form-actions">
                <button type="button" class="sv-button" onClick={() => setEditing(null)}>
                  {m(i18n(), "ui.action.cancel")}
                </button>
                <button type="submit" class="sv-button sv-button--primary" disabled={editPending()}>
                  {m(i18n(), "ui.action.save")}
                </button>
              </div>
            </form>
          </Dialog>
        )}
      </Show>

      <Show when={withdrawing()}>
        {(report) => (
          <ConfirmDialog
            title={m(i18n(), "ui.action.withdraw")}
            body={m(i18n(), "ui.confirm.withdraw-report")}
            ctx={`report:${report().id}`}
            confirmLabel={m(i18n(), "ui.action.withdraw")}
            cancelLabel={m(i18n(), "ui.action.cancel")}
            onConfirm={() => withdraw(report())}
            onClose={() => setWithdrawing(null)}
          />
        )}
      </Show>
    </>
  );
}

export default function MyReports() {
  return <ClientPage requiresAuth fallback={(toast) => <PublicFeedContent toast={toast} />}>{(toast) => <MyReportsContent toast={toast} />}</ClientPage>;
}

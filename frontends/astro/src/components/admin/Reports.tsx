import { createSignal, For, onMount, Show } from "solid-js";
import BookmarkContext from "../BookmarkContext";
import Pagination from "../Pagination";
import { api, jsonBody, queryString } from "../../lib/api";
import { formatDate } from "../../lib/format";
import { i18n, m } from "../../lib/i18n";
import type { Page, Report, ReportStatus } from "../../lib/types";
import { REPORT_STATUSES } from "../../lib/types";
import ClientPage from "../ClientPage";

export function ReportsContent() {
  const [status, setStatus] = createSignal<ReportStatus>("open");
  const [page, setPage] = createSignal(0);
  const [reports, setReports] = createSignal<Page<Report> | null>(null);
  const [loading, setLoading] = createSignal(true);
  const [error, setError] = createSignal<Error | null>(null);
  const [resolvingId, setResolvingId] = createSignal<string | null>(null);
  let loadRequest = 0;

  async function load() {
    const request = ++loadRequest;
    setLoading(true);
    setError(null);
    try {
      const nextReports = await api<Page<Report>>(`/api/v1/admin/reports${queryString({ status: status(), page: page() })}`);
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

  async function resolve(report: Report, resolution: ReportStatus) {
    setResolvingId(report.id);
    setError(null);
    try {
      await api<Report>(`/api/v1/admin/reports/${report.id}`, {
        method: "PUT",
        ...jsonBody({ resolution }),
      });
      await load();
    } catch (caught) {
      setError(caught instanceof Error ? caught : new Error(String(caught)));
    } finally {
      setResolvingId(null);
    }
  }

  onMount(() => {
    void load();
  });

  return (
    <>
      <h1 class="sv-page-title">{m(i18n(), "ui.admin.reports")}</h1>
      <div class="sv-toolbar">
        <select
          class="sv-select"
          value={status()}
          onChange={(event) => {
            setStatus(event.currentTarget.value as ReportStatus);
            setPage(0);
            void load();
          }}
        >
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
        <div class="sv-empty">{m(i18n(), "ui.reports.empty")}</div>
      ) : (
        <>
          <div class="sv-table-wrap">
            <table class="sv-table">
              <thead>
                <tr>
                  <th scope="col">{m(i18n(), "ui.field.created-at")}</th>
                  <th scope="col">{m(i18n(), "ui.field.bookmark")}</th>
                  <th scope="col">{m(i18n(), "ui.field.reporter")}</th>
                  <th scope="col">{m(i18n(), "ui.field.reason")}</th>
                  <th scope="col">{m(i18n(), "ui.field.comment")}</th>
                  <th scope="col"><span class="sv-visually-hidden">{m(i18n(), "ui.field.actions")}</span></th>
                </tr>
              </thead>
              <tbody>
                <For each={reports()!.items}>
                  {(report) => (
                    <tr data-ctx={`report:${report.id}`}>
                      <td><time dateTime={report.createdAt}>{formatDate(report.createdAt, i18n().resolvedLanguage)}</time></td>
                      <td><BookmarkContext bookmarkId={report.bookmarkId} /></td>
                      <td>{report.reporter}</td>
                      <td><span class="sv-badge">{m(i18n(), `ui.report.reason.${report.reason}`)}</span></td>
                      <td>{report.comment}</td>
                      <td class="sv-cell-actions">
                        <Show
                          when={report.status === "open"}
                          fallback={
                            <>
                              <span class={`sv-badge${report.status === "actioned" ? " sv-badge--danger" : ""}`}>
                                {m(i18n(), `ui.report.status.${report.status}`)}
                              </span>
                              <Show
                                when={report.status === "actioned"}
                                fallback={
                                  <button type="button" class="sv-button sv-button--ghost sv-button--sm" disabled={resolvingId() === report.id} onClick={() => resolve(report, "actioned")}>
                                    {m(i18n(), "ui.action.action")}
                                  </button>
                                }
                              >
                                <button type="button" class="sv-button sv-button--ghost sv-button--sm" disabled={resolvingId() === report.id} onClick={() => resolve(report, "dismissed")}>
                                  {m(i18n(), "ui.action.dismiss")}
                                </button>
                              </Show>
                              <button type="button" class="sv-button sv-button--ghost sv-button--sm" disabled={resolvingId() === report.id} onClick={() => resolve(report, "open")}>
                                {m(i18n(), "ui.action.reopen")}
                              </button>
                            </>
                          }
                        >
                          <button type="button" class="sv-button sv-button--sm" disabled={resolvingId() === report.id} onClick={() => resolve(report, "dismissed")}>
                            {m(i18n(), "ui.action.dismiss")}
                          </button>
                          <button type="button" class="sv-button sv-button--danger sv-button--sm" disabled={resolvingId() === report.id} onClick={() => resolve(report, "actioned")}>
                            {m(i18n(), "ui.action.action")}
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
    </>
  );
}

export default function Reports() {
  return <ClientPage requiredRole="moderator">{() => <ReportsContent />}</ClientPage>;
}

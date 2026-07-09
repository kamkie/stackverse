import { $, component$, useStore, useVisibleTask$ } from "@builder.io/qwik";
import BookmarkContext from "../../components/BookmarkContext";
import Pagination from "../../components/Pagination";
import { api, jsonBody, queryString } from "../../lib/api";
import { formatDate } from "../../lib/format";
import { m, type I18nState } from "../../lib/i18n";
import type { Page, Report, ReportStatus } from "../../lib/types";
import { REPORT_STATUSES } from "../../lib/types";

export default component$<{ i18n: I18nState }>((props) => {
  const state = useStore<{
    status: ReportStatus;
    page: number;
    reports: Page<Report> | null;
    loading: boolean;
    error: string;
    resolvingId: string;
  }>({
    status: "open",
    page: 0,
    reports: null,
    loading: true,
    error: "",
    resolvingId: "",
  });

  const load$ = $(async () => {
    state.loading = true;
    state.error = "";
    try {
      let nextReports = await api<Page<Report>>(
        `/api/v1/admin/reports${queryString({ status: state.status, page: state.page })}`,
      );
      if (nextReports.items.length === 0 && state.page > 0) {
        state.page = Math.max(0, nextReports.totalPages - 1);
        nextReports = await api<Page<Report>>(
          `/api/v1/admin/reports${queryString({ status: state.status, page: state.page })}`,
        );
      }
      state.reports = nextReports;
    } catch (caught) {
      state.error = caught instanceof Error ? caught.message : String(caught);
    } finally {
      state.loading = false;
    }
  });

  const resolve$ = $(async (report: Report, resolution: ReportStatus) => {
    state.resolvingId = report.id;
    state.error = "";
    try {
      await api<Report>(`/api/v1/admin/reports/${report.id}`, {
        method: "PUT",
        ...jsonBody({ resolution }),
      });
      await load$();
    } catch (caught) {
      state.error = caught instanceof Error ? caught.message : String(caught);
    } finally {
      state.resolvingId = "";
    }
  });

  useVisibleTask$(() => {
    void load$();
  });

  return (
    <>
      <h1 class="sv-page-title">{m(props.i18n, "ui.admin.reports")}</h1>
      <div class="sv-toolbar">
        <select
          class="sv-select"
          value={state.status}
          onChange$={(event: Event) => {
            state.status = (event.target as HTMLInputElement)
              .value as ReportStatus;
            state.page = 0;
            void load$();
          }}
        >
          {REPORT_STATUSES.map((option) => (
            <option key={option} value={option}>
              {m(props.i18n, `ui.report.status.${option}`)}
            </option>
          ))}
        </select>
      </div>

      {state.loading && !state.reports ? (
        <div class="sv-loading">
          <span class="sv-spinner" />
        </div>
      ) : state.error ? (
        <div class="sv-alert sv-alert--danger" role="alert">
          {state.error}
        </div>
      ) : !state.reports || state.reports.items.length === 0 ? (
        <div class="sv-empty">{m(props.i18n, "ui.reports.empty")}</div>
      ) : (
        <>
          <div class="sv-table-wrap">
            <table class="sv-table">
              <thead>
                <tr>
                  <th scope="col">{m(props.i18n, "ui.field.created-at")}</th>
                  <th scope="col">{m(props.i18n, "ui.field.bookmark")}</th>
                  <th scope="col">{m(props.i18n, "ui.field.reporter")}</th>
                  <th scope="col">{m(props.i18n, "ui.field.reason")}</th>
                  <th scope="col">{m(props.i18n, "ui.field.comment")}</th>
                  <th scope="col">
                    <span class="sv-visually-hidden">
                      {m(props.i18n, "ui.field.actions")}
                    </span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {state.reports.items.map((report) => (
                  <tr key={report.id} data-ctx={`report:${report.id}`}>
                    <td>
                      <time dateTime={report.createdAt}>
                        {formatDate(
                          report.createdAt,
                          props.i18n.resolvedLanguage,
                        )}
                      </time>
                    </td>
                    <td>
                      <BookmarkContext
                        i18n={props.i18n}
                        bookmarkId={report.bookmarkId}
                      />
                    </td>
                    <td>{report.reporter}</td>
                    <td>
                      <span class="sv-badge">
                        {m(props.i18n, `ui.report.reason.${report.reason}`)}
                      </span>
                    </td>
                    <td>{report.comment}</td>
                    <td class="sv-cell-actions">
                      {report.status === "open" ? (
                        <>
                          <button
                            type="button"
                            class="sv-button sv-button--sm"
                            disabled={state.resolvingId === report.id}
                            onClick$={() => resolve$(report, "dismissed")}
                          >
                            {m(props.i18n, "ui.action.dismiss")}
                          </button>
                          <button
                            type="button"
                            class="sv-button sv-button--danger sv-button--sm"
                            disabled={state.resolvingId === report.id}
                            onClick$={() => resolve$(report, "actioned")}
                          >
                            {m(props.i18n, "ui.action.action")}
                          </button>
                        </>
                      ) : (
                        <>
                          <span
                            class={`sv-badge${report.status === "actioned" ? " sv-badge--danger" : ""}`}
                          >
                            {m(props.i18n, `ui.report.status.${report.status}`)}
                          </span>
                          {report.status === "actioned" ? (
                            <button
                              type="button"
                              class="sv-button sv-button--ghost sv-button--sm"
                              disabled={state.resolvingId === report.id}
                              onClick$={() => resolve$(report, "dismissed")}
                            >
                              {m(props.i18n, "ui.action.dismiss")}
                            </button>
                          ) : (
                            <button
                              type="button"
                              class="sv-button sv-button--ghost sv-button--sm"
                              disabled={state.resolvingId === report.id}
                              onClick$={() => resolve$(report, "actioned")}
                            >
                              {m(props.i18n, "ui.action.action")}
                            </button>
                          )}
                          <button
                            type="button"
                            class="sv-button sv-button--ghost sv-button--sm"
                            disabled={state.resolvingId === report.id}
                            onClick$={() => resolve$(report, "open")}
                          >
                            {m(props.i18n, "ui.action.reopen")}
                          </button>
                        </>
                      )}
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
    </>
  );
});

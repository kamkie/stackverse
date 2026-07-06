import { $, component$, useStore, useVisibleTask$ } from "@builder.io/qwik";
import Pagination from "../../components/Pagination";
import { api, queryString } from "../../lib/api";
import { endOfDayIso, formatDate } from "../../lib/format";
import { m, type I18nState } from "../../lib/i18n";
import type { AuditEntry, Page } from "../../lib/types";

export default component$<{ i18n: I18nState }>((props) => {
  const state = useStore<{
    actor: string;
    action: string;
    from: string;
    to: string;
    page: number;
    audit: Page<AuditEntry> | null;
    loading: boolean;
    error: string;
    loadRequest: number;
    filterTimer: number | undefined;
  }>({
    actor: "",
    action: "",
    from: "",
    to: "",
    page: 0,
    audit: null,
    loading: true,
    error: "",
    loadRequest: 0,
    filterTimer: undefined,
  });

  const clearPendingFilterReload$ = $(() => {
    if (state.filterTimer !== undefined) {
      window.clearTimeout(state.filterTimer);
      state.filterTimer = undefined;
    }
  });

  const load$ = $(async (options: { clear?: boolean } = {}) => {
    const request = ++state.loadRequest;
    state.loading = true;
    state.error = "";
    if (options.clear) state.audit = null;
    try {
      const nextAudit = await api<Page<AuditEntry>>(
        `/api/v1/admin/audit-log${queryString({
          actor: state.actor,
          action: state.action,
          from: state.from ? new Date(`${state.from}T00:00:00`).toISOString() : "",
          to: state.to ? endOfDayIso(state.to) : "",
          page: state.page,
        })}`,
      );
      if (request === state.loadRequest) state.audit = nextAudit;
    } catch (caught) {
      if (request === state.loadRequest) {
        state.error = caught instanceof Error ? caught.message : String(caught);
      }
    } finally {
      if (request === state.loadRequest) state.loading = false;
    }
  });

  const reloadFirstPage$ = $(() => {
    void clearPendingFilterReload$();
    state.page = 0;
    void load$({ clear: true });
  });

  const scheduleFilterReload$ = $(() => {
    void clearPendingFilterReload$();
    state.page = 0;
    state.filterTimer = window.setTimeout(() => {
      state.filterTimer = undefined;
      void load$({ clear: true });
    }, 200);
  });

  useVisibleTask$(({ cleanup }) => {
    void load$();
    cleanup(() => {
      void clearPendingFilterReload$();
    });
  });

  return (
    <>
      <h1 class="sv-page-title">{m(props.i18n, "ui.admin.audit")}</h1>
      <div class="sv-toolbar">
        <input
          class="sv-input"
          placeholder={m(props.i18n, "ui.field.actor")}
          value={state.actor}
          onInput$={(event: Event) => {
            state.actor = (event.target as HTMLInputElement).value;
            void scheduleFilterReload$();
          }}
        />
        <input
          class="sv-input"
          placeholder={m(props.i18n, "ui.audit.action.placeholder")}
          aria-label={m(props.i18n, "ui.audit.action.placeholder")}
          value={state.action}
          onInput$={(event: Event) => {
            state.action = (event.target as HTMLInputElement).value;
            void scheduleFilterReload$();
          }}
        />
        <label class="sv-toolbar-field">
          <span class="sv-label">{m(props.i18n, "ui.field.from")}</span>
          <input
            type="date"
            class="sv-input"
            value={state.from}
            onChange$={(event: Event) => {
              state.from = (event.target as HTMLInputElement).value;
              void reloadFirstPage$();
            }}
          />
        </label>
        <label class="sv-toolbar-field">
          <span class="sv-label">{m(props.i18n, "ui.field.to")}</span>
          <input
            type="date"
            class="sv-input"
            value={state.to}
            onChange$={(event: Event) => {
              state.to = (event.target as HTMLInputElement).value;
              void reloadFirstPage$();
            }}
          />
        </label>
        <button
          type="button"
          class="sv-button sv-button--ghost"
          onClick$={() => {
            void clearPendingFilterReload$();
            state.actor = "";
            state.action = "";
            state.from = "";
            state.to = "";
            state.page = 0;
            void load$({ clear: true });
          }}
        >
          {m(props.i18n, "ui.action.clear-filters")}
        </button>
      </div>

      {state.loading && !state.audit ? (
        <div class="sv-loading"><span class="sv-spinner" /></div>
      ) : state.error ? (
        <div class="sv-alert sv-alert--danger" role="alert">{state.error}</div>
      ) : state.audit ? (
        <>
          <div class="sv-table-wrap">
            <table class="sv-table">
              <thead>
                <tr>
                  <th scope="col">{m(props.i18n, "ui.field.created-at")}</th>
                  <th scope="col">{m(props.i18n, "ui.field.actor")}</th>
                  <th scope="col">{m(props.i18n, "ui.field.action")}</th>
                  <th scope="col">{m(props.i18n, "ui.field.target")}</th>
                </tr>
              </thead>
              <tbody>
                {state.audit.items.map((entry) => (
                  <tr key={entry.id}>
                    <td><time dateTime={entry.createdAt}>{formatDate(entry.createdAt, props.i18n.resolvedLanguage)}</time></td>
                    <td>{entry.actor}</td>
                    <td><span class="sv-badge">{entry.action}</span></td>
                    <td class="sv-cell-mono">{entry.targetType}/{entry.targetId.slice(0, 8)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pagination
            i18n={props.i18n}
            page={state.page}
            totalPages={state.audit.totalPages}
            onPage$={(next) => {
              state.page = next;
              void load$();
            }}
          />
        </>
      ) : null}
    </>
  );
});

import { createSignal, For, onCleanup, onSettled } from "solid-js";
import Pagination from "../../components/Pagination";
import { api, queryString } from "../../lib/api";
import { endOfDayIso, formatDate } from "../../lib/format";
import { i18n, m } from "../../lib/i18n";
import type { AuditEntry, Page } from "../../lib/types";

const knownActions = [
  "message.created",
  "message.updated",
  "message.deleted",
  "report.resolved",
  "bookmark.status-changed",
  "user.blocked",
  "user.unblocked",
];

export default function AuditLogPage() {
  const [actor, setActor] = createSignal("");
  const [action, setAction] = createSignal("");
  const [from, setFrom] = createSignal("");
  const [to, setTo] = createSignal("");
  const [page, setPage] = createSignal(0);
  const [audit, setAudit] = createSignal<Page<AuditEntry> | null>(null);
  const [loading, setLoading] = createSignal(true);
  const [error, setError] = createSignal<Error | null>(null);
  let loadRequest = 0;
  let filterTimer: number | undefined;

  function clearPendingFilterReload() {
    if (filterTimer !== undefined) {
      window.clearTimeout(filterTimer);
      filterTimer = undefined;
    }
  }

  async function load(options: {
    page?: number;
    actor?: string;
    action?: string;
    from?: string;
    to?: string;
    clear?: boolean;
  } = {}) {
    const request = ++loadRequest;
    setLoading(true);
    setError(null);
    if (options.clear) setAudit(null);

    const start = options.from ?? from();
    const end = options.to ?? to();

    try {
      const nextAudit = await api<Page<AuditEntry>>(
        `/api/v1/admin/audit-log${queryString({
          actor: options.actor ?? actor(),
          action: options.action ?? action(),
          from: start ? new Date(`${start}T00:00:00`).toISOString() : "",
          to: end ? endOfDayIso(end) : "",
          page: options.page ?? page(),
        })}`,
      );
      if (request === loadRequest) setAudit(nextAudit);
    } catch (caught) {
      if (request === loadRequest) {
        setError(caught instanceof Error ? caught : new Error(String(caught)));
      }
    } finally {
      if (request === loadRequest) setLoading(false);
    }
  }

  function reloadFirstPage(dates: { from?: string; to?: string }) {
    clearPendingFilterReload();
    setPage(0);
    void load({ ...dates, page: 0, clear: true });
  }

  function scheduleFilterReload() {
    clearPendingFilterReload();
    setPage(0);
    filterTimer = window.setTimeout(() => {
      filterTimer = undefined;
      void load({ page: 0, clear: true });
    }, 200);
  }

  function clearFilters() {
    clearPendingFilterReload();
    setActor("");
    setAction("");
    setFrom("");
    setTo("");
    setPage(0);
    void load({ actor: "", action: "", from: "", to: "", page: 0, clear: true });
  }

  onSettled(() => {
    void load();
  });

  onCleanup(clearPendingFilterReload);

  return (
    <>
      <h1 class="sv-page-title">{m(i18n(), "ui.admin.audit")}</h1>
      <div class="sv-toolbar">
        <input
          class="sv-input"
          placeholder={m(i18n(), "ui.field.actor")}
          value={actor()}
          onInput={(event) => {
            setActor(event.currentTarget.value);
            scheduleFilterReload();
          }}
        />
        <input
          class="sv-input"
          placeholder={m(i18n(), "ui.audit.action.placeholder")}
          aria-label={m(i18n(), "ui.audit.action.placeholder")}
          list="audit-log-known-actions"
          value={action()}
          onInput={(event) => {
            setAction(event.currentTarget.value);
            scheduleFilterReload();
          }}
        />
        <datalist id="audit-log-known-actions">
          <For each={knownActions}>{(item) => <option value={item} />}</For>
        </datalist>
        <label class="sv-toolbar-field">
          <span class="sv-label">{m(i18n(), "ui.field.from")}</span>
          <input
            type="date"
            class="sv-input"
            value={from()}
            onChange={(event) => {
              const from = event.currentTarget.value;
              setFrom(from);
              reloadFirstPage({ from });
            }}
          />
        </label>
        <label class="sv-toolbar-field">
          <span class="sv-label">{m(i18n(), "ui.field.to")}</span>
          <input
            type="date"
            class="sv-input"
            value={to()}
            onChange={(event) => {
              const to = event.currentTarget.value;
              setTo(to);
              reloadFirstPage({ to });
            }}
          />
        </label>
        <button type="button" class="sv-button sv-button--ghost" onClick={clearFilters}>
          {m(i18n(), "ui.action.clear-filters")}
        </button>
      </div>

      {loading() && !audit() ? (
        <div class="sv-loading"><span class="sv-spinner" /></div>
      ) : error() ? (
        <div class="sv-alert sv-alert--danger" role="alert">{error()?.message}</div>
      ) : audit() ? (
        <>
          <div class="sv-table-wrap">
            <table class="sv-table">
              <thead>
                <tr>
                  <th scope="col">{m(i18n(), "ui.field.created-at")}</th>
                  <th scope="col">{m(i18n(), "ui.field.actor")}</th>
                  <th scope="col">{m(i18n(), "ui.field.action")}</th>
                  <th scope="col">{m(i18n(), "ui.field.target")}</th>
                </tr>
              </thead>
              <tbody>
                <For each={audit()!.items}>
                  {(entry) => (
                    <tr>
                      <td><time datetime={entry.createdAt}>{formatDate(entry.createdAt, i18n().resolvedLanguage)}</time></td>
                      <td>{entry.actor}</td>
                      <td><span class="sv-badge">{entry.action}</span></td>
                      <td class="sv-cell-mono">{entry.targetType}/{entry.targetId.slice(0, 8)}</td>
                    </tr>
                  )}
                </For>
              </tbody>
            </table>
          </div>
          <Pagination page={page()} totalPages={audit()!.totalPages} onPage={(next) => {
            setPage(next);
            void load({ page: next });
          }} />
        </>
      ) : null}
    </>
  );
}

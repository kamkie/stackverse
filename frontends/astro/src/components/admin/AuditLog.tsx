import { createSignal, For, onCleanup, onMount } from "solid-js";
import Pagination from "../Pagination";
import { api, queryString } from "../../lib/api";
import { endOfDayIso, formatDate } from "../../lib/format";
import { i18n, m } from "../../lib/i18n";
import type { AuditEntry, Page } from "../../lib/types";
import ClientPage from "../ClientPage";

const knownActions = [
  "message.created",
  "message.updated",
  "message.deleted",
  "report.resolved",
  "bookmark.status-changed",
  "user.blocked",
  "user.unblocked",
];

export function AuditLogContent() {
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

  async function load(options: { clear?: boolean } = {}) {
    const request = ++loadRequest;
    setLoading(true);
    setError(null);
    if (options.clear) setAudit(null);

    try {
      const nextAudit = await api<Page<AuditEntry>>(
        `/api/v1/admin/audit-log${queryString({
          actor: actor(),
          action: action(),
          from: from() ? new Date(`${from()}T00:00:00`).toISOString() : "",
          to: to() ? endOfDayIso(to()) : "",
          page: page(),
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

  function reloadFirstPage() {
    clearPendingFilterReload();
    setPage(0);
    void load({ clear: true });
  }

  function scheduleFilterReload() {
    clearPendingFilterReload();
    setPage(0);
    filterTimer = window.setTimeout(() => {
      filterTimer = undefined;
      void load({ clear: true });
    }, 200);
  }

  function clearFilters() {
    clearPendingFilterReload();
    setActor("");
    setAction("");
    setFrom("");
    setTo("");
    setPage(0);
    void load({ clear: true });
  }

  onMount(() => {
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
              setFrom(event.currentTarget.value);
              reloadFirstPage();
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
              setTo(event.currentTarget.value);
              reloadFirstPage();
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
                      <td><time dateTime={entry.createdAt}>{formatDate(entry.createdAt, i18n().resolvedLanguage)}</time></td>
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
            void load();
          }} />
        </>
      ) : null}
    </>
  );
}

export default function AuditLog() {
  return <ClientPage requiredRole="admin">{() => <AuditLogContent />}</ClientPage>;
}

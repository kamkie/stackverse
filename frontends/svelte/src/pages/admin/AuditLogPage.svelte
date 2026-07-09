<script lang="ts">
  import { onDestroy, onMount } from "svelte";
  import { api, queryString } from "../../lib/api";
  import { endOfDayIso, formatDate } from "../../lib/format";
  import { i18n, m } from "../../lib/i18n";
  import type { AuditEntry, Page } from "../../lib/types";
  import Pagination from "../../components/Pagination.svelte";
  import { fromStore } from "svelte/store";

  const knownActions = [
    "message.created",
    "message.updated",
    "message.deleted",
    "report.resolved",
    "bookmark.status-changed",
    "user.blocked",
    "user.unblocked",
  ];

  let actor = $state("");
  let action = $state("");
  let from = $state("");
  let to = $state("");
  let page = $state(0);
  let audit: Page<AuditEntry> | null = $state(null);
  let loading = $state(true);
  let error: Error | null = $state(null);
  let loadRequest = 0;
  let filterTimer: number | undefined = undefined;
  const i18nState = fromStore(i18n);

  function clearPendingFilterReload() {
    if (filterTimer !== undefined) {
      window.clearTimeout(filterTimer);
      filterTimer = undefined;
    }
  }

  async function load(options: { clear?: boolean } = {}) {
    const request = ++loadRequest;
    loading = true;
    error = null;
    if (options.clear) {
      audit = null;
    }

    try {
      const nextAudit = await api<Page<AuditEntry>>(
        `/api/v1/admin/audit-log${queryString({
          actor,
          action,
          from: from ? new Date(`${from}T00:00:00`).toISOString() : "",
          to: to ? endOfDayIso(to) : "",
          page,
        })}`,
      );
      if (request === loadRequest) {
        audit = nextAudit;
      }
    } catch (caught) {
      if (request === loadRequest) {
        error = caught instanceof Error ? caught : new Error(String(caught));
      }
    } finally {
      if (request === loadRequest) {
        loading = false;
      }
    }
  }

  function filterValue(event: Event) {
    return (event.currentTarget as HTMLInputElement).value;
  }

  function reloadFirstPage() {
    clearPendingFilterReload();
    page = 0;
    void load({ clear: true });
  }

  function scheduleFilterReload() {
    clearPendingFilterReload();
    page = 0;
    filterTimer = window.setTimeout(() => {
      filterTimer = undefined;
      void load({ clear: true });
    }, 200);
  }

  function clearFilters() {
    clearPendingFilterReload();
    actor = "";
    action = "";
    from = "";
    to = "";
    page = 0;
    void load({ clear: true });
  }

  onMount(() => {
    void load();
  });

  onDestroy(clearPendingFilterReload);
</script>

<h1 class="sv-page-title">{m(i18nState.current, "ui.admin.audit")}</h1>
<div class="sv-toolbar">
  <input
    class="sv-input"
    placeholder={m(i18nState.current, "ui.field.actor")}
    value={actor}
    oninput={(event) => {
      actor = filterValue(event);
      scheduleFilterReload();
    }}
  />
  <input
    class="sv-input"
    placeholder={m(i18nState.current, "ui.audit.action.placeholder")}
    aria-label={m(i18nState.current, "ui.audit.action.placeholder")}
    list="audit-log-known-actions"
    value={action}
    oninput={(event) => {
      action = filterValue(event);
      scheduleFilterReload();
    }}
  />
  <datalist id="audit-log-known-actions">
    {#each knownActions as item (item)}
      <option value={item}></option>
    {/each}
  </datalist>
  <label class="sv-toolbar-field">
    <span class="sv-label">{m(i18nState.current, "ui.field.from")}</span>
    <input
      type="date"
      class="sv-input"
      value={from}
      onchange={(event) => {
        from = filterValue(event);
        reloadFirstPage();
      }}
    />
  </label>
  <label class="sv-toolbar-field">
    <span class="sv-label">{m(i18nState.current, "ui.field.to")}</span>
    <input
      type="date"
      class="sv-input"
      value={to}
      onchange={(event) => {
        to = filterValue(event);
        reloadFirstPage();
      }}
    />
  </label>
  <button
    type="button"
    class="sv-button sv-button--ghost"
    onclick={clearFilters}
  >
    {m(i18nState.current, "ui.action.clear-filters")}
  </button>
</div>

{#if loading && !audit}
  <div class="sv-loading"><span class="sv-spinner"></span></div>
{:else if error}
  <div class="sv-alert sv-alert--danger" role="alert">{error.message}</div>
{:else if audit}
  <div class="sv-table-wrap">
    <table class="sv-table">
      <thead>
        <tr>
          <th scope="col">{m(i18nState.current, "ui.field.created-at")}</th>
          <th scope="col">{m(i18nState.current, "ui.field.actor")}</th>
          <th scope="col">{m(i18nState.current, "ui.field.action")}</th>
          <th scope="col">{m(i18nState.current, "ui.field.target")}</th>
        </tr>
      </thead>
      <tbody>
        {#each audit.items as entry (entry.id)}
          <tr>
            <td
              ><time dateTime={entry.createdAt}
                >{formatDate(
                  entry.createdAt,
                  i18nState.current.resolvedLanguage,
                )}</time
              ></td
            >
            <td>{entry.actor}</td>
            <td><span class="sv-badge">{entry.action}</span></td>
            <td class="sv-cell-mono"
              >{entry.targetType}/{entry.targetId.slice(0, 8)}</td
            >
          </tr>
        {/each}
      </tbody>
    </table>
  </div>
  <Pagination
    {page}
    totalPages={audit.totalPages}
    onPage={(next) => {
      page = next;
      void load();
    }}
  />
{/if}

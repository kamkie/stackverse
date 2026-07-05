<script lang="ts">
  import { onMount } from "svelte";
  import { api, queryString } from "../../lib/api";
  import { endOfDayIso, formatDate } from "../../lib/format";
  import { i18n, m } from "../../lib/i18n";
  import type { AuditEntry, Page } from "../../lib/types";
  import Pagination from "../../components/Pagination.svelte";

  const knownActions = [
    "message.created",
    "message.updated",
    "message.deleted",
    "report.resolved",
    "bookmark.status-changed",
    "user.blocked",
    "user.unblocked",
  ];

  let actor = "";
  let action = "";
  let from = "";
  let to = "";
  let page = 0;
  let audit: Page<AuditEntry> | null = null;
  let loading = true;
  let error: Error | null = null;
  let loadRequest = 0;

  async function load() {
    const request = ++loadRequest;
    loading = true;
    error = null;
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
    page = 0;
    void load();
  }

  function clearFilters() {
    actor = "";
    action = "";
    from = "";
    to = "";
    page = 0;
    void load();
  }

  onMount(() => {
    void load();
  });
</script>

<h1 class="sv-page-title">{m($i18n, "ui.admin.audit")}</h1>
<div class="sv-toolbar">
  <input
    class="sv-input"
    placeholder={m($i18n, "ui.field.actor")}
    value={actor}
    on:input={(event) => {
      actor = filterValue(event);
      reloadFirstPage();
    }}
  />
  <input
    class="sv-input"
    placeholder={m($i18n, "ui.audit.action.placeholder")}
    aria-label={m($i18n, "ui.audit.action.placeholder")}
    list="audit-log-known-actions"
    value={action}
    on:input={(event) => {
      action = filterValue(event);
      reloadFirstPage();
    }}
  />
  <datalist id="audit-log-known-actions">
    {#each knownActions as item}
      <option value={item}></option>
    {/each}
  </datalist>
  <label class="sv-toolbar-field">
    <span class="sv-label">{m($i18n, "ui.field.from")}</span>
    <input
      type="date"
      class="sv-input"
      value={from}
      on:change={(event) => {
        from = filterValue(event);
        reloadFirstPage();
      }}
    />
  </label>
  <label class="sv-toolbar-field">
    <span class="sv-label">{m($i18n, "ui.field.to")}</span>
    <input
      type="date"
      class="sv-input"
      value={to}
      on:change={(event) => {
        to = filterValue(event);
        reloadFirstPage();
      }}
    />
  </label>
  <button type="button" class="sv-button sv-button--ghost" on:click={clearFilters}>
    {m($i18n, "ui.action.clear-filters")}
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
          <th scope="col">{m($i18n, "ui.field.created-at")}</th>
          <th scope="col">{m($i18n, "ui.field.actor")}</th>
          <th scope="col">{m($i18n, "ui.field.action")}</th>
          <th scope="col">{m($i18n, "ui.field.target")}</th>
        </tr>
      </thead>
      <tbody>
        {#each audit.items as entry (entry.id)}
          <tr>
            <td><time dateTime={entry.createdAt}>{formatDate(entry.createdAt, $i18n.resolvedLanguage)}</time></td>
            <td>{entry.actor}</td>
            <td><span class="sv-badge">{entry.action}</span></td>
            <td class="sv-cell-mono">{entry.targetType}/{entry.targetId.slice(0, 8)}</td>
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

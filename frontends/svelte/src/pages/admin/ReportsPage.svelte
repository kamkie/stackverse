<script lang="ts">
  import { onMount } from "svelte";
  import { api, jsonBody, queryString } from "../../lib/api";
  import { formatDate } from "../../lib/format";
  import { i18n, m } from "../../lib/i18n";
  import type { Page, Report, ReportStatus } from "../../lib/types";
  import { REPORT_STATUSES } from "../../lib/types";
  import BookmarkContext from "../../components/BookmarkContext.svelte";
  import Pagination from "../../components/Pagination.svelte";

  const statuses = REPORT_STATUSES;

  let status: ReportStatus = "open";
  let page = 0;
  let reports: Page<Report> | null = null;
  let loading = true;
  let error: Error | null = null;
  let resolvingId: string | null = null;

  async function load() {
    loading = true;
    error = null;
    try {
      reports = await api<Page<Report>>(
        `/api/v1/admin/reports${queryString({ status, page })}`,
      );
    } catch (caught) {
      error = caught instanceof Error ? caught : new Error(String(caught));
    } finally {
      loading = false;
    }
  }

  async function resolve(report: Report, resolution: ReportStatus) {
    resolvingId = report.id;
    error = null;
    try {
      await api<Report>(`/api/v1/admin/reports/${report.id}`, {
        method: "PUT",
        ...jsonBody({ resolution }),
      });
      await load();
    } catch (caught) {
      error = caught instanceof Error ? caught : new Error(String(caught));
    } finally {
      resolvingId = null;
    }
  }

  onMount(() => {
    void load();
  });
</script>

<h1 class="sv-page-title">{m($i18n, "ui.admin.reports")}</h1>
<div class="sv-toolbar">
  <select
    class="sv-select"
    bind:value={status}
    on:change={() => {
      page = 0;
      void load();
    }}
  >
    {#each statuses as option}
      <option value={option}>{m($i18n, `ui.report.status.${option}`)}</option>
    {/each}
  </select>
</div>

{#if loading && !reports}
  <div class="sv-loading"><span class="sv-spinner"></span></div>
{:else if error}
  <div class="sv-alert sv-alert--danger" role="alert">{error.message}</div>
{:else if !reports || reports.items.length === 0}
  <div class="sv-empty">{m($i18n, "ui.reports.empty")}</div>
{:else}
  <div class="sv-table-wrap">
    <table class="sv-table">
      <thead>
        <tr>
          <th scope="col">{m($i18n, "ui.field.created-at")}</th>
          <th scope="col">{m($i18n, "ui.field.bookmark")}</th>
          <th scope="col">{m($i18n, "ui.field.reporter")}</th>
          <th scope="col">{m($i18n, "ui.field.reason")}</th>
          <th scope="col">{m($i18n, "ui.field.comment")}</th>
          <th scope="col"><span class="sv-visually-hidden">{m($i18n, "ui.field.actions")}</span></th>
        </tr>
      </thead>
      <tbody>
        {#each reports.items as report (report.id)}
          <tr data-ctx={`report:${report.id}`}>
            <td><time dateTime={report.createdAt}>{formatDate(report.createdAt, $i18n.resolvedLanguage)}</time></td>
            <td><BookmarkContext bookmarkId={report.bookmarkId} /></td>
            <td>{report.reporter}</td>
            <td><span class="sv-badge">{m($i18n, `ui.report.reason.${report.reason}`)}</span></td>
            <td>{report.comment}</td>
            <td class="sv-cell-actions">
              {#if report.status === "open"}
                <button type="button" class="sv-button sv-button--sm" disabled={resolvingId === report.id} on:click={() => resolve(report, "dismissed")}>
                  {m($i18n, "ui.action.dismiss")}
                </button>
                <button type="button" class="sv-button sv-button--danger sv-button--sm" disabled={resolvingId === report.id} on:click={() => resolve(report, "actioned")}>
                  {m($i18n, "ui.action.action")}
                </button>
              {:else}
                <span class={`sv-badge${report.status === "actioned" ? " sv-badge--danger" : ""}`}>
                  {m($i18n, `ui.report.status.${report.status}`)}
                </span>
                {#if report.status === "actioned"}
                  <button type="button" class="sv-button sv-button--ghost sv-button--sm" disabled={resolvingId === report.id} on:click={() => resolve(report, "dismissed")}>
                    {m($i18n, "ui.action.dismiss")}
                  </button>
                {:else}
                  <button type="button" class="sv-button sv-button--ghost sv-button--sm" disabled={resolvingId === report.id} on:click={() => resolve(report, "actioned")}>
                    {m($i18n, "ui.action.action")}
                  </button>
                {/if}
                <button type="button" class="sv-button sv-button--ghost sv-button--sm" disabled={resolvingId === report.id} on:click={() => resolve(report, "open")}>
                  {m($i18n, "ui.action.reopen")}
                </button>
              {/if}
            </td>
          </tr>
        {/each}
      </tbody>
    </table>
  </div>
  <Pagination
    {page}
    totalPages={reports.totalPages}
    onPage={(next) => {
      page = next;
      void load();
    }}
  />
{/if}

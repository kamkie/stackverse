<script lang="ts">
  import { onMount } from "svelte";
  import { ApiError, api, fieldErrorFor, jsonBody, queryString } from "../lib/api";
  import { formatDate } from "../lib/format";
  import { i18n, m } from "../lib/i18n";
  import { removeReported } from "../lib/reportedStore";
  import type { Page, Report, ReportInput, ReportReason, ReportStatus } from "../lib/types";
  import BookmarkContext from "../components/BookmarkContext.svelte";
  import ConfirmDialog from "../components/ConfirmDialog.svelte";
  import Dialog from "../components/Dialog.svelte";
  import Field from "../components/Field.svelte";
  import Pagination from "../components/Pagination.svelte";

  export let toast: (message: string, tone?: "success" | "danger") => void;

  const statuses: ReportStatus[] = ["open", "dismissed", "actioned"];
  const reasons: ReportReason[] = ["spam", "offensive", "broken-link", "other"];

  let status: ReportStatus | "" = "";
  let page = 0;
  let reports: Page<Report> | null = null;
  let loading = true;
  let error: Error | null = null;
  let editing: Report | null = null;
  let withdrawing: Report | null = null;
  let editReason: ReportReason = "spam";
  let editComment = "";
  let editError: unknown = undefined;
  let editPending = false;

  async function load() {
    loading = true;
    error = null;
    try {
      reports = await api<Page<Report>>(
        `/api/v1/reports${queryString({ status, page })}`,
      );
    } catch (caught) {
      error = caught instanceof Error ? caught : new Error(String(caught));
    } finally {
      loading = false;
    }
  }

  function openEdit(report: Report) {
    editing = report;
    editReason = report.reason;
    editComment = report.comment ?? "";
    editError = undefined;
  }

  async function saveEdit() {
    if (!editing) return;
    editPending = true;
    editError = undefined;
    try {
      const body: ReportInput = {
        reason: editReason,
        ...(editComment ? { comment: editComment } : {}),
      };
      await api<Report>(`/api/v1/reports/${editing.id}`, {
        method: "PUT",
        ...jsonBody(body),
      });
      editing = null;
      toast(m($i18n, "ui.toast.report-updated"));
      await load();
    } catch (caught) {
      editError = caught;
    } finally {
      editPending = false;
    }
  }

  async function withdraw(report: Report) {
    await api<void>(`/api/v1/reports/${report.id}`, { method: "DELETE" });
    removeReported(report.bookmarkId);
    withdrawing = null;
    toast(m($i18n, "ui.toast.report-withdrawn"));
    await load();
  }

  onMount(() => {
    void load();
  });
</script>

<section class="sv-content">
  <h1 class="sv-page-title">{m($i18n, "ui.nav.my-reports")}</h1>
  <div class="sv-toolbar">
    <select
      class="sv-select"
      aria-label={m($i18n, "ui.field.status")}
      bind:value={status}
      on:change={() => {
        page = 0;
        void load();
      }}
    >
      <option value="">{m($i18n, "ui.my-reports.filter.all-statuses")}</option>
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
    <div class="sv-empty">{m($i18n, "ui.my-reports.empty")}</div>
  {:else}
    <div class="sv-table-wrap">
      <table class="sv-table">
        <thead>
          <tr>
            <th scope="col">{m($i18n, "ui.field.created-at")}</th>
            <th scope="col">{m($i18n, "ui.field.bookmark")}</th>
            <th scope="col">{m($i18n, "ui.field.reason")}</th>
            <th scope="col">{m($i18n, "ui.field.comment")}</th>
            <th scope="col">{m($i18n, "ui.field.status")}</th>
            <th scope="col"><span class="sv-visually-hidden">{m($i18n, "ui.field.actions")}</span></th>
          </tr>
        </thead>
        <tbody>
          {#each reports.items as report (report.id)}
            <tr data-ctx={`report:${report.id}`}>
              <td><time dateTime={report.createdAt}>{formatDate(report.createdAt, $i18n.resolvedLanguage)}</time></td>
              <td><BookmarkContext bookmarkId={report.bookmarkId} /></td>
              <td><span class="sv-badge">{m($i18n, `ui.report.reason.${report.reason}`)}</span></td>
              <td>{report.comment}</td>
              <td>
                <span class={`sv-badge${report.status === "actioned" ? " sv-badge--danger" : ""}`}>
                  {m($i18n, `ui.report.status.${report.status}`)}
                </span>
                {#if report.resolutionNote}
                  <div class="sv-field-hint">{report.resolutionNote}</div>
                {/if}
              </td>
              <td class="sv-cell-actions">
                {#if report.status === "open"}
                  <button type="button" class="sv-button sv-button--ghost sv-button--sm" on:click={() => openEdit(report)}>
                    {m($i18n, "ui.action.edit")}
                  </button>
                  <button type="button" class="sv-button sv-button--ghost sv-button--sm" on:click={() => (withdrawing = report)}>
                    {m($i18n, "ui.action.withdraw")}
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
</section>

{#if editing}
  <Dialog title={m($i18n, "ui.my-reports.dialog.edit")} ctx={`report:${editing.id}`} on:close={() => (editing = null)}>
    <form class="sv-form" on:submit|preventDefault={saveEdit}>
      <Field label={m($i18n, "ui.field.reason")} error={fieldErrorFor(editError, "reason")}>
        <select class="sv-select" bind:value={editReason}>
          {#each reasons as option}
            <option value={option}>{m($i18n, `ui.report.reason.${option}`)}</option>
          {/each}
        </select>
      </Field>
      <Field label={m($i18n, "ui.field.comment")} error={fieldErrorFor(editError, "comment")}>
        <textarea class="sv-textarea" bind:value={editComment}></textarea>
      </Field>
      {#if editError instanceof ApiError && editError.status === 409}
        <div class="sv-alert sv-alert--warning" role="alert">{editError.message}</div>
      {/if}
      <div class="sv-form-actions">
        <button type="button" class="sv-button" on:click={() => (editing = null)}>
          {m($i18n, "ui.action.cancel")}
        </button>
        <button type="submit" class="sv-button sv-button--primary" disabled={editPending}>
          {m($i18n, "ui.action.save")}
        </button>
      </div>
    </form>
  </Dialog>
{/if}

{#if withdrawing}
  <ConfirmDialog
    title={m($i18n, "ui.action.withdraw")}
    body={m($i18n, "ui.confirm.withdraw-report")}
    ctx={`report:${withdrawing.id}`}
    confirmLabel={m($i18n, "ui.action.withdraw")}
    cancelLabel={m($i18n, "ui.action.cancel")}
    onConfirm={() => withdraw(withdrawing as Report)}
    onClose={() => (withdrawing = null)}
  />
{/if}

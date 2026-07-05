<script lang="ts">
  import { onMount } from "svelte";
  import { ApiError, api, fieldErrorFor, jsonBody, queryString } from "../lib/api";
  import { formatDate } from "../lib/format";
  import { i18n, m } from "../lib/i18n";
  import { removeReported } from "../lib/reportedStore";
  import type { Page, Report, ReportInput, ReportReason, ReportStatus } from "../lib/types";
  import { REPORT_REASONS, REPORT_STATUSES } from "../lib/types";
  import BookmarkContext from "../components/BookmarkContext.svelte";
  import ConfirmDialog from "../components/ConfirmDialog.svelte";
  import Dialog from "../components/Dialog.svelte";
  import Field from "../components/Field.svelte";
  import Pagination from "../components/Pagination.svelte";
  import { fromStore } from "svelte/store";

  let { toast }: { toast: (message: string, tone?: "success" | "danger") => void } = $props();

  const statuses = REPORT_STATUSES;
  const reasons = REPORT_REASONS;

  let status: ReportStatus | "" = $state("");
  let page = $state(0);
  let reports: Page<Report> | null = $state(null);
  let loading = $state(true);
  let error: Error | null = $state(null);
  let editing: Report | null = $state(null);
  let withdrawing: Report | null = $state(null);
  let editReason: ReportReason = $state(reasons[0]);
  let editComment = $state("");
  let editError: unknown = $state(undefined);
  let editPending = $state(false);
  const i18nState = fromStore(i18n);

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

  async function saveEdit(event: SubmitEvent) {
    event.preventDefault();
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
      toast(m(i18nState.current, "ui.toast.report-updated"));
      await load();
    } catch (caught) {
      editError = caught;
    } finally {
      editPending = false;
    }
  }

  async function withdraw(report: Report) {
    try {
      await api<void>(`/api/v1/reports/${report.id}`, { method: "DELETE" });
      removeReported(report.bookmarkId);
      withdrawing = null;
      toast(m(i18nState.current, "ui.toast.report-withdrawn"));
      await load();
    } catch (caught) {
      toast(caught instanceof Error ? caught.message : String(caught), "danger");
    }
  }

  onMount(() => {
    void load();
  });
</script>

<section class="sv-content">
  <h1 class="sv-page-title">{m(i18nState.current, "ui.nav.my-reports")}</h1>
  <div class="sv-toolbar">
    <select
      class="sv-select"
      aria-label={m(i18nState.current, "ui.field.status")}
      bind:value={status}
      onchange={() => {
        page = 0;
        void load();
      }}
    >
      <option value="">{m(i18nState.current, "ui.my-reports.filter.all-statuses")}</option>
      {#each statuses as option}
        <option value={option}>{m(i18nState.current, `ui.report.status.${option}`)}</option>
      {/each}
    </select>
  </div>

  {#if loading && !reports}
    <div class="sv-loading"><span class="sv-spinner"></span></div>
  {:else if error}
    <div class="sv-alert sv-alert--danger" role="alert">{error.message}</div>
  {:else if !reports || reports.items.length === 0}
    <div class="sv-empty">{m(i18nState.current, "ui.my-reports.empty")}</div>
  {:else}
    <div class="sv-table-wrap">
      <table class="sv-table">
        <thead>
          <tr>
            <th scope="col">{m(i18nState.current, "ui.field.created-at")}</th>
            <th scope="col">{m(i18nState.current, "ui.field.bookmark")}</th>
            <th scope="col">{m(i18nState.current, "ui.field.reason")}</th>
            <th scope="col">{m(i18nState.current, "ui.field.comment")}</th>
            <th scope="col">{m(i18nState.current, "ui.field.status")}</th>
            <th scope="col"><span class="sv-visually-hidden">{m(i18nState.current, "ui.field.actions")}</span></th>
          </tr>
        </thead>
        <tbody>
          {#each reports.items as report (report.id)}
            <tr data-ctx={`report:${report.id}`}>
              <td><time dateTime={report.createdAt}>{formatDate(report.createdAt, i18nState.current.resolvedLanguage)}</time></td>
              <td><BookmarkContext bookmarkId={report.bookmarkId} /></td>
              <td><span class="sv-badge">{m(i18nState.current, `ui.report.reason.${report.reason}`)}</span></td>
              <td>{report.comment}</td>
              <td>
                <span class={`sv-badge${report.status === "actioned" ? " sv-badge--danger" : ""}`}>
                  {m(i18nState.current, `ui.report.status.${report.status}`)}
                </span>
                {#if report.resolutionNote}
                  <div class="sv-field-hint">{report.resolutionNote}</div>
                {/if}
              </td>
              <td class="sv-cell-actions">
                {#if report.status === "open"}
                  <button type="button" class="sv-button sv-button--ghost sv-button--sm" onclick={() => openEdit(report)}>
                    {m(i18nState.current, "ui.action.edit")}
                  </button>
                  <button type="button" class="sv-button sv-button--ghost sv-button--sm" onclick={() => (withdrawing = report)}>
                    {m(i18nState.current, "ui.action.withdraw")}
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
  <Dialog title={m(i18nState.current, "ui.my-reports.dialog.edit")} ctx={`report:${editing.id}`} onClose={() => (editing = null)}>
    <form class="sv-form" onsubmit={saveEdit}>
      <Field label={m(i18nState.current, "ui.field.reason")} error={fieldErrorFor(editError, "reason")}>
        <select class="sv-select" bind:value={editReason}>
          {#each reasons as option}
            <option value={option}>{m(i18nState.current, `ui.report.reason.${option}`)}</option>
          {/each}
        </select>
      </Field>
      <Field label={m(i18nState.current, "ui.field.comment")} error={fieldErrorFor(editError, "comment")}>
        <textarea class="sv-textarea" bind:value={editComment}></textarea>
      </Field>
      {#if editError instanceof ApiError && editError.status === 409}
        <div class="sv-alert sv-alert--warning" role="alert">{editError.message}</div>
      {/if}
      <div class="sv-form-actions">
        <button type="button" class="sv-button" onclick={() => (editing = null)}>
          {m(i18nState.current, "ui.action.cancel")}
        </button>
        <button type="submit" class="sv-button sv-button--primary" disabled={editPending}>
          {m(i18nState.current, "ui.action.save")}
        </button>
      </div>
    </form>
  </Dialog>
{/if}

{#if withdrawing}
  <ConfirmDialog
    title={m(i18nState.current, "ui.action.withdraw")}
    body={m(i18nState.current, "ui.confirm.withdraw-report")}
    ctx={`report:${withdrawing.id}`}
    confirmLabel={m(i18nState.current, "ui.action.withdraw")}
    cancelLabel={m(i18nState.current, "ui.action.cancel")}
    onConfirm={() => withdraw(withdrawing as Report)}
    onClose={() => (withdrawing = null)}
  />
{/if}

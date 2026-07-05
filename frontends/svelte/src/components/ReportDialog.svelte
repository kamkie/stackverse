<script lang="ts">
  import { ApiError, api, fieldErrorFor, jsonBody } from "../lib/api";
  import { i18n, m } from "../lib/i18n";
  import { markReported } from "../lib/reportedStore";
  import type { Bookmark, Report, ReportInput, ReportReason } from "../lib/types";
  import Dialog from "./Dialog.svelte";
  import Field from "./Field.svelte";

  export let bookmark: Bookmark;
  export let toast: (message: string, tone?: "success" | "danger") => void;
  export let onDone: () => void | Promise<void>;
  export let onClose: () => void;

  const reasons: ReportReason[] = ["spam", "offensive", "broken-link", "other"];
  let reason: ReportReason = "spam";
  let comment = "";
  let error: unknown = undefined;
  let pending = false;

  async function submit() {
    pending = true;
    error = undefined;
    const body: ReportInput = { reason, ...(comment ? { comment } : {}) };
    try {
      await api<Report>(`/api/v1/bookmarks/${bookmark.id}/reports`, {
        method: "POST",
        ...jsonBody(body),
      });
      markReported(bookmark.id);
      toast(m($i18n, "ui.toast.report-submitted"));
      await onDone();
      onClose();
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 409) {
        markReported(bookmark.id);
        toast(m($i18n, "ui.toast.report-duplicate"));
        await onDone();
        onClose();
        return;
      }
      error = caught;
    } finally {
      pending = false;
    }
  }
</script>

<Dialog title={m($i18n, "ui.action.report")} ctx={`bookmark:${bookmark.id}`} on:close={onClose}>
  <form class="sv-form" on:submit|preventDefault={submit}>
    <Field label={m($i18n, "ui.field.reason")} error={fieldErrorFor(error, "reason")}>
      <select class="sv-select" bind:value={reason}>
        {#each reasons as option}
          <option value={option}>{m($i18n, `ui.report.reason.${option}`)}</option>
        {/each}
      </select>
    </Field>
    <Field label={m($i18n, "ui.field.comment")} error={fieldErrorFor(error, "comment")}>
      <textarea class="sv-textarea" bind:value={comment}></textarea>
    </Field>
    <div class="sv-form-actions">
      <button type="button" class="sv-button" on:click={onClose}>
        {m($i18n, "ui.action.cancel")}
      </button>
      <button type="submit" class="sv-button sv-button--primary" disabled={pending}>
        {m($i18n, "ui.action.report")}
      </button>
    </div>
  </form>
</Dialog>

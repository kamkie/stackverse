<script lang="ts">
  import { ApiError, api, fieldErrorFor, jsonBody } from "../lib/api";
  import { i18n, m } from "../lib/i18n";
  import { markReported } from "../lib/reportedStore";
  import type { Bookmark, Report, ReportInput, ReportReason } from "../lib/types";
  import { REPORT_REASONS } from "../lib/types";
  import { fromStore } from "svelte/store";
  import Dialog from "./Dialog.svelte";
  import Field from "./Field.svelte";

  interface Props {
    bookmark: Bookmark;
    toast: (message: string, tone?: "success" | "danger") => void;
    onDone: () => void | Promise<void>;
    onClose: () => void;
  }

  let { bookmark, toast, onDone, onClose }: Props = $props();

  const reasons = REPORT_REASONS;
  let reason: ReportReason = $state(reasons[0]);
  let comment = $state("");
  let error: unknown = $state(undefined);
  let pending = $state(false);
  const i18nState = fromStore(i18n);

  async function submit(event: SubmitEvent) {
    event.preventDefault();
    pending = true;
    error = undefined;
    const body: ReportInput = { reason, ...(comment ? { comment } : {}) };
    try {
      await api<Report>(`/api/v1/bookmarks/${bookmark.id}/reports`, {
        method: "POST",
        ...jsonBody(body),
      });
      markReported(bookmark.id);
      toast(m(i18nState.current, "ui.toast.report-submitted"));
      await onDone();
      onClose();
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 409) {
        markReported(bookmark.id);
        toast(m(i18nState.current, "ui.toast.report-duplicate"));
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

<Dialog title={m(i18nState.current, "ui.action.report")} ctx={`bookmark:${bookmark.id}`} {onClose}>
  <form class="sv-form" onsubmit={submit}>
    <Field label={m(i18nState.current, "ui.field.reason")} error={fieldErrorFor(error, "reason")}>
      <select class="sv-select" bind:value={reason}>
        {#each reasons as option}
          <option value={option}>{m(i18nState.current, `ui.report.reason.${option}`)}</option>
        {/each}
      </select>
    </Field>
    <Field label={m(i18nState.current, "ui.field.comment")} error={fieldErrorFor(error, "comment")}>
      <textarea class="sv-textarea" bind:value={comment}></textarea>
    </Field>
    <div class="sv-form-actions">
      <button type="button" class="sv-button" onclick={onClose}>
        {m(i18nState.current, "ui.action.cancel")}
      </button>
      <button type="submit" class="sv-button sv-button--primary" disabled={pending}>
        {m(i18nState.current, "ui.action.report")}
      </button>
    </div>
  </form>
</Dialog>

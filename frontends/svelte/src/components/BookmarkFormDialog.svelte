<script lang="ts">
  import { ApiError, api, fieldErrorFor, jsonBody } from "../lib/api";
  import { i18n, m } from "../lib/i18n";
  import type { Bookmark, BookmarkInput, Visibility } from "../lib/types";
  import Dialog from "./Dialog.svelte";
  import Field from "./Field.svelte";

  export let bookmark: Bookmark | undefined = undefined;
  export let onSaved: () => void | Promise<void>;
  export let onClose: () => void;

  let url = bookmark?.url ?? "";
  let title = bookmark?.title ?? "";
  let notes = bookmark?.notes ?? "";
  let tags = bookmark?.tags.join(" ") ?? "";
  let visibility: Visibility = bookmark?.visibility ?? "private";
  let error: unknown = undefined;
  let pending = false;

  async function submit() {
    pending = true;
    error = undefined;
    const body: BookmarkInput = {
      url,
      title,
      ...(notes ? { notes } : {}),
      tags: tags.split(/[\s,]+/).filter(Boolean),
      visibility,
    };
    try {
      if (bookmark) {
        await api<Bookmark>(`/api/v1/bookmarks/${bookmark.id}`, {
          method: "PUT",
          ...jsonBody(body),
        });
      } else {
        await api<Bookmark>("/api/v1/bookmarks", {
          method: "POST",
          ...jsonBody(body),
        });
      }
      await onSaved();
      onClose();
    } catch (caught) {
      error = caught;
    } finally {
      pending = false;
    }
  }
</script>

<Dialog
  title={m($i18n, bookmark ? "ui.bookmarks.dialog.edit" : "ui.bookmarks.dialog.add")}
  ctx={bookmark ? `bookmark:${bookmark.id}` : undefined}
  on:close={onClose}
>
  <form class="sv-form" on:submit|preventDefault={submit}>
    <Field label={m($i18n, "ui.field.url")} error={fieldErrorFor(error, "url")}>
      <input class="sv-input" bind:value={url} />
    </Field>
    <Field label={m($i18n, "ui.field.title")} error={fieldErrorFor(error, "title")}>
      <input class="sv-input" bind:value={title} />
    </Field>
    <Field label={m($i18n, "ui.field.notes")} error={fieldErrorFor(error, "notes")}>
      <textarea class="sv-textarea" bind:value={notes}></textarea>
    </Field>
    <Field
      label={m($i18n, "ui.field.tags")}
      hint={m($i18n, "ui.field.tags.hint")}
      error={fieldErrorFor(error, "tags")}
    >
      <input class="sv-input" bind:value={tags} />
    </Field>
    <Field label={m($i18n, "ui.field.visibility")} error={fieldErrorFor(error, "visibility")}>
      <select class="sv-select" bind:value={visibility}>
        <option value="private">{m($i18n, "ui.visibility.private")}</option>
        <option value="public">{m($i18n, "ui.visibility.public")}</option>
      </select>
    </Field>
    {#if error instanceof ApiError && error.status === 409}
      <div class="sv-alert sv-alert--warning" role="alert">
        {m($i18n, "error.bookmark.hidden-publish")}
      </div>
    {/if}
    <div class="sv-form-actions">
      <button type="button" class="sv-button" on:click={onClose}>
        {m($i18n, "ui.action.cancel")}
      </button>
      <button type="submit" class="sv-button sv-button--primary" disabled={pending}>
        {m($i18n, "ui.action.save")}
      </button>
    </div>
  </form>
</Dialog>

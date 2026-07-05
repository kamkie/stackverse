<script lang="ts">
  import { ApiError, api, fieldErrorFor, jsonBody } from "../lib/api";
  import { i18n, m } from "../lib/i18n";
  import type { Bookmark, BookmarkInput, Visibility } from "../lib/types";
  import { fromStore } from "svelte/store";
  import Dialog from "./Dialog.svelte";
  import Field from "./Field.svelte";

  interface Props {
    bookmark?: Bookmark;
    onSaved: () => void | Promise<void>;
    onClose: () => void;
  }

  let { bookmark = undefined, onSaved, onClose }: Props = $props();
  const initial = initialFields();
  let url = $state(initial.url);
  let title = $state(initial.title);
  let notes = $state(initial.notes);
  let tags = $state(initial.tags);
  let visibility: Visibility = $state(initial.visibility);
  let error: unknown = $state(undefined);
  let pending = $state(false);
  const i18nState = fromStore(i18n);

  function initialFields() {
    return {
      url: bookmark?.url ?? "",
      title: bookmark?.title ?? "",
      notes: bookmark?.notes ?? "",
      tags: bookmark?.tags.join(" ") ?? "",
      visibility: bookmark?.visibility ?? "private",
    };
  }

  async function submit(event: SubmitEvent) {
    event.preventDefault();
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
  title={m(i18nState.current, bookmark ? "ui.bookmarks.dialog.edit" : "ui.bookmarks.dialog.add")}
  ctx={bookmark ? `bookmark:${bookmark.id}` : undefined}
  {onClose}
>
  <form class="sv-form" onsubmit={submit}>
    <Field label={m(i18nState.current, "ui.field.url")} error={fieldErrorFor(error, "url")}>
      <input class="sv-input" bind:value={url} />
    </Field>
    <Field label={m(i18nState.current, "ui.field.title")} error={fieldErrorFor(error, "title")}>
      <input class="sv-input" bind:value={title} />
    </Field>
    <Field label={m(i18nState.current, "ui.field.notes")} error={fieldErrorFor(error, "notes")}>
      <textarea class="sv-textarea" bind:value={notes}></textarea>
    </Field>
    <Field
      label={m(i18nState.current, "ui.field.tags")}
      hint={m(i18nState.current, "ui.field.tags.hint")}
      error={fieldErrorFor(error, "tags")}
    >
      <input class="sv-input" bind:value={tags} />
    </Field>
    <Field label={m(i18nState.current, "ui.field.visibility")} error={fieldErrorFor(error, "visibility")}>
      <select class="sv-select" bind:value={visibility}>
        <option value="private">{m(i18nState.current, "ui.visibility.private")}</option>
        <option value="public">{m(i18nState.current, "ui.visibility.public")}</option>
      </select>
    </Field>
    {#if error instanceof ApiError && error.status === 409}
      <div class="sv-alert sv-alert--warning" role="alert">
        {m(i18nState.current, "error.bookmark.hidden-publish")}
      </div>
    {/if}
    <div class="sv-form-actions">
      <button type="button" class="sv-button" onclick={onClose}>
        {m(i18nState.current, "ui.action.cancel")}
      </button>
      <button type="submit" class="sv-button sv-button--primary" disabled={pending}>
        {m(i18nState.current, "ui.action.save")}
      </button>
    </div>
  </form>
</Dialog>

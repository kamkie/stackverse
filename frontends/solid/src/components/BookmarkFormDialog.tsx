import { createSignal, Show } from "solid-js";
import { ApiError, api, fieldErrorFor, jsonBody } from "../lib/api";
import { i18n, m } from "../lib/i18n";
import type { Bookmark, BookmarkInput, Visibility } from "../lib/types";
import Dialog from "./Dialog";
import Field from "./Field";

interface Props {
  bookmark?: Bookmark;
  onSaved: () => void | Promise<void>;
  onClose: () => void;
}

export default function BookmarkFormDialog(props: Props) {
  const [url, setUrl] = createSignal(props.bookmark?.url ?? "");
  const [title, setTitle] = createSignal(props.bookmark?.title ?? "");
  const [notes, setNotes] = createSignal(props.bookmark?.notes ?? "");
  const [tags, setTags] = createSignal(props.bookmark?.tags.join(" ") ?? "");
  const [visibility, setVisibility] = createSignal<Visibility>(
    props.bookmark?.visibility ?? "private",
  );
  const [error, setError] = createSignal<unknown>(undefined);
  const [pending, setPending] = createSignal(false);

  async function submit(event: SubmitEvent) {
    event.preventDefault();
    setPending(true);
    setError(undefined);
    const body: BookmarkInput = {
      url: url(),
      title: title(),
      ...(notes() ? { notes: notes() } : {}),
      tags: tags().split(/[\s,]+/).filter(Boolean),
      visibility: visibility(),
    };
    try {
      if (props.bookmark) {
        await api<Bookmark>(`/api/v1/bookmarks/${props.bookmark.id}`, {
          method: "PUT",
          ...jsonBody(body),
        });
      } else {
        await api<Bookmark>("/api/v1/bookmarks", {
          method: "POST",
          ...jsonBody(body),
        });
      }
      await props.onSaved();
      props.onClose();
    } catch (caught) {
      setError(caught);
    } finally {
      setPending(false);
    }
  }

  return (
    <Dialog
      title={m(i18n(), props.bookmark ? "ui.bookmarks.dialog.edit" : "ui.bookmarks.dialog.add")}
      ctx={props.bookmark ? `bookmark:${props.bookmark.id}` : undefined}
      onClose={props.onClose}
    >
      <form class="sv-form" onSubmit={submit}>
        <Field label={m(i18n(), "ui.field.url")} error={fieldErrorFor(error(), "url")}>
          <input class="sv-input" value={url()} onInput={(event) => setUrl(event.currentTarget.value)} />
        </Field>
        <Field label={m(i18n(), "ui.field.title")} error={fieldErrorFor(error(), "title")}>
          <input class="sv-input" value={title()} onInput={(event) => setTitle(event.currentTarget.value)} />
        </Field>
        <Field label={m(i18n(), "ui.field.notes")} error={fieldErrorFor(error(), "notes")}>
          <textarea class="sv-textarea" value={notes()} onInput={(event) => setNotes(event.currentTarget.value)} />
        </Field>
        <Field
          label={m(i18n(), "ui.field.tags")}
          hint={m(i18n(), "ui.field.tags.hint")}
          error={fieldErrorFor(error(), "tags")}
        >
          <input class="sv-input" value={tags()} onInput={(event) => setTags(event.currentTarget.value)} />
        </Field>
        <Field label={m(i18n(), "ui.field.visibility")} error={fieldErrorFor(error(), "visibility")}>
          <select
            class="sv-select"
            value={visibility()}
            onChange={(event) => setVisibility(event.currentTarget.value as Visibility)}
          >
            <option value="private">{m(i18n(), "ui.visibility.private")}</option>
            <option value="public">{m(i18n(), "ui.visibility.public")}</option>
          </select>
        </Field>
        <Show when={error() instanceof ApiError && (error() as ApiError).status === 409}>
          <div class="sv-alert sv-alert--warning" role="alert">
            {m(i18n(), "error.bookmark.hidden-publish")}
          </div>
        </Show>
        <div class="sv-form-actions">
          <button type="button" class="sv-button" onClick={props.onClose}>
            {m(i18n(), "ui.action.cancel")}
          </button>
          <button type="submit" class="sv-button sv-button--primary" disabled={pending()}>
            {m(i18n(), "ui.action.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

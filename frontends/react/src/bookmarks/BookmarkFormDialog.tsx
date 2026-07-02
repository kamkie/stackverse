import { useState, type SubmitEvent } from "react";
import { ApiError, fieldErrorFor } from "../api/problem";
import { Dialog } from "../components/Dialog";
import { Field } from "../components/Field";
import { useI18n } from "../i18n/I18nProvider";
import {
  useCreateBookmark,
  useUpdateBookmark,
  type Bookmark,
  type BookmarkInput,
} from "./queries";

interface BookmarkFormDialogProps {
  /** Absent for create, present for edit. */
  bookmark?: Bookmark | undefined;
  onClose: () => void;
}

/**
 * Create/edit form. Validation failures come back as RFC 9457 problem
 * documents whose `errors` array is rendered on the matching fields —
 * never as a generic toast.
 */
export function BookmarkFormDialog({ bookmark, onClose }: BookmarkFormDialogProps) {
  const { t } = useI18n();
  const create = useCreateBookmark();
  const update = useUpdateBookmark();
  const mutation = bookmark ? update : create;

  const [url, setUrl] = useState(bookmark?.url ?? "");
  const [title, setTitle] = useState(bookmark?.title ?? "");
  const [notes, setNotes] = useState(bookmark?.notes ?? "");
  const [tags, setTags] = useState(bookmark?.tags?.join(" ") ?? "");
  const [visibility, setVisibility] = useState(bookmark?.visibility ?? "private");

  const error = mutation.error;

  function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    const body: BookmarkInput = {
      url,
      title,
      ...(notes ? { notes } : {}),
      tags: tags.split(/[\s,]+/).filter(Boolean),
      visibility,
    };
    const options = { onSuccess: onClose };
    if (bookmark) update.mutate({ id: bookmark.id, body }, options);
    else create.mutate(body, options);
  }

  return (
    <Dialog title={bookmark ? t("ui.action.edit") : t("ui.action.add")} onClose={onClose}>
      <form className="sv-form" onSubmit={submit}>
        <Field label={t("ui.field.url")} error={fieldErrorFor(error, "url")}>
          <input
            className="sv-input"
            value={url}
            onChange={(e) => setUrl(e.target.value)}
            autoFocus
          />
        </Field>
        <Field label={t("ui.field.title")} error={fieldErrorFor(error, "title")}>
          <input
            className="sv-input"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
          />
        </Field>
        <Field label={t("ui.field.notes")} error={fieldErrorFor(error, "notes")}>
          <textarea
            className="sv-textarea"
            value={notes}
            onChange={(e) => setNotes(e.target.value)}
          />
        </Field>
        <Field label={t("ui.field.tags")} error={fieldErrorFor(error, "tags")}>
          <input
            className="sv-input"
            value={tags}
            onChange={(e) => setTags(e.target.value)}
          />
        </Field>
        <Field label={t("ui.field.visibility")} error={fieldErrorFor(error, "visibility")}>
          <select
            className="sv-select"
            value={visibility}
            onChange={(e) => setVisibility(e.target.value as BookmarkInput["visibility"])}
          >
            <option value="private">private</option>
            <option value="public">public</option>
          </select>
        </Field>
        {error instanceof ApiError && error.status === 409 && (
          <div className="sv-alert sv-alert--warning" role="alert">
            {t("error.bookmark.hidden-publish")}
          </div>
        )}
        <div className="sv-form-actions">
          <button type="button" className="sv-button" onClick={onClose}>
            {t("ui.action.cancel")}
          </button>
          <button
            type="submit"
            className="sv-button sv-button--primary"
            disabled={mutation.isPending}
          >
            {t("ui.action.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

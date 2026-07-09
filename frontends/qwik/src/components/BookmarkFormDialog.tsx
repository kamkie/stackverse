import { component$, useSignal, type PropFunction } from "@builder.io/qwik";
import { api, apiStatus, fieldErrorFor, jsonBody } from "../lib/api";
import { formText } from "../lib/form";
import { m, type I18nState } from "../lib/i18n";
import type { Bookmark, BookmarkInput, Visibility } from "../lib/types";
import Dialog from "./Dialog";
import Field from "./Field";

interface Props {
  i18n: I18nState;
  bookmark?: Bookmark;
  onSaved$: PropFunction<() => void | Promise<void>>;
  onClose$: PropFunction<() => void>;
}

export default component$<Props>((props) => {
  const url = useSignal(props.bookmark?.url ?? "");
  const title = useSignal(props.bookmark?.title ?? "");
  const notes = useSignal(props.bookmark?.notes ?? "");
  const tags = useSignal(props.bookmark?.tags.join(" ") ?? "");
  const visibility = useSignal<Visibility>(
    props.bookmark?.visibility ?? "private",
  );
  const error = useSignal<unknown>(undefined);
  const pending = useSignal(false);

  return (
    <Dialog
      title={m(
        props.i18n,
        props.bookmark ? "ui.bookmarks.dialog.edit" : "ui.bookmarks.dialog.add",
      )}
      ctx={props.bookmark ? `bookmark:${props.bookmark.id}` : undefined}
      onClose$={props.onClose$}
    >
      <form
        class="sv-form"
        preventdefault:submit
        onSubmit$={async (event: Event) => {
          pending.value = true;
          error.value = undefined;
          const form = event.target as HTMLFormElement;
          url.value = formText(form, "url");
          title.value = formText(form, "title");
          notes.value = formText(form, "notes");
          tags.value = formText(form, "tags");
          visibility.value = formText(form, "visibility") as Visibility;
          const body: BookmarkInput = {
            url: url.value,
            title: title.value,
            ...(notes.value ? { notes: notes.value } : {}),
            tags: tags.value.split(/[\s,]+/).filter(Boolean),
            visibility: visibility.value,
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
            await props.onSaved$();
            await props.onClose$();
          } catch (caught) {
            error.value = caught;
          } finally {
            pending.value = false;
          }
        }}
      >
        <Field
          label={m(props.i18n, "ui.field.url")}
          error={fieldErrorFor(error.value, "url")}
        >
          <input
            name="url"
            class="sv-input"
            value={url.value}
            onInput$={(event: Event) =>
              (url.value = (event.target as HTMLInputElement).value)
            }
          />
        </Field>
        <Field
          label={m(props.i18n, "ui.field.title")}
          error={fieldErrorFor(error.value, "title")}
        >
          <input
            name="title"
            class="sv-input"
            value={title.value}
            onInput$={(event: Event) =>
              (title.value = (event.target as HTMLInputElement).value)
            }
          />
        </Field>
        <Field
          label={m(props.i18n, "ui.field.notes")}
          error={fieldErrorFor(error.value, "notes")}
        >
          <textarea
            name="notes"
            class="sv-textarea"
            value={notes.value}
            onInput$={(event: Event) =>
              (notes.value = (event.target as HTMLInputElement).value)
            }
          />
        </Field>
        <Field
          label={m(props.i18n, "ui.field.tags")}
          hint={m(props.i18n, "ui.field.tags.hint")}
          error={fieldErrorFor(error.value, "tags")}
        >
          <input
            name="tags"
            class="sv-input"
            value={tags.value}
            onInput$={(event: Event) =>
              (tags.value = (event.target as HTMLInputElement).value)
            }
          />
        </Field>
        <Field
          label={m(props.i18n, "ui.field.visibility")}
          error={fieldErrorFor(error.value, "visibility")}
        >
          <select
            name="visibility"
            class="sv-select"
            value={visibility.value}
            onChange$={(event: Event) =>
              (visibility.value = (event.target as HTMLInputElement)
                .value as Visibility)
            }
          >
            <option value="private">
              {m(props.i18n, "ui.visibility.private")}
            </option>
            <option value="public">
              {m(props.i18n, "ui.visibility.public")}
            </option>
          </select>
        </Field>
        {apiStatus(error.value) === 409 ? (
          <div class="sv-alert sv-alert--warning" role="alert">
            {m(props.i18n, "error.bookmark.hidden-publish")}
          </div>
        ) : null}
        <div class="sv-form-actions">
          <button type="button" class="sv-button" onClick$={props.onClose$}>
            {m(props.i18n, "ui.action.cancel")}
          </button>
          <button
            type="submit"
            class="sv-button sv-button--primary"
            disabled={pending.value}
          >
            {m(props.i18n, "ui.action.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
});

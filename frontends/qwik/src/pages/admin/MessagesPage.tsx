import { $, component$, useSignal, useStore, useVisibleTask$, type PropFunction } from "@builder.io/qwik";
import ConfirmDialog from "../../components/ConfirmDialog";
import Dialog from "../../components/Dialog";
import Field from "../../components/Field";
import Pagination from "../../components/Pagination";
import { ApiError, api, fieldErrorFor, jsonBody, queryString } from "../../lib/api";
import { loadBundle, m, SUPPORTED_LANGUAGES, type I18nState } from "../../lib/i18n";
import type { Message, MessageInput, Page } from "../../lib/types";

interface Props {
  i18n: I18nState;
  onBundle$: PropFunction<(next: I18nState) => void>;
  toast$: PropFunction<(message: string, tone?: "success" | "danger") => void>;
}

type EditingMessage = Message | "new";

export default component$<Props>((props) => {
  const state = useStore<{
    q: string;
    language: string;
    page: number;
    messages: Page<Message> | null;
    loading: boolean;
    error: string;
    editing: EditingMessage | null;
    deleting: Message | null;
  }>({
    q: "",
    language: "",
    page: 0,
    messages: null,
    loading: true,
    error: "",
    editing: null,
    deleting: null,
  });
  const key = useSignal("");
  const messageLanguage = useSignal("en");
  const text = useSignal("");
  const description = useSignal("");
  const formError = useSignal<unknown>(undefined);

  const load$ = $(async () => {
    state.loading = true;
    state.error = "";
    try {
      let nextMessages = await api<Page<Message>>(`/api/v1/messages${queryString({ q: state.q, language: state.language, page: state.page })}`);
      if (nextMessages.items.length === 0 && state.page > 0) {
        state.page = Math.max(0, nextMessages.totalPages - 1);
        nextMessages = await api<Page<Message>>(`/api/v1/messages${queryString({ q: state.q, language: state.language, page: state.page })}`);
      }
      state.messages = nextMessages;
    } catch (caught) {
      state.error = caught instanceof Error ? caught.message : String(caught);
    } finally {
      state.loading = false;
    }
  });

  const refreshBundle$ = $(async () => {
    await props.onBundle$(await loadBundle(props.i18n.lang));
  });

  const openCreate$ = $(() => {
    state.editing = "new";
    key.value = "";
    messageLanguage.value = "en";
    text.value = "";
    description.value = "";
    formError.value = undefined;
  });

  const openEdit$ = $((message: Message) => {
    state.editing = message;
    key.value = message.key;
    messageLanguage.value = message.language;
    text.value = message.text;
    description.value = message.description ?? "";
    formError.value = undefined;
  });

  useVisibleTask$(() => {
    void load$();
  });

  return (
    <>
      <h1 class="sv-page-title">{m(props.i18n, "ui.admin.messages")}</h1>
      <div class="sv-toolbar">
        <input
          class="sv-input"
          placeholder={m(props.i18n, "ui.messages.search.placeholder")}
          aria-label={m(props.i18n, "ui.messages.search.placeholder")}
          value={state.q}
          onInput$={(event: Event) => {
            state.q = (event.target as HTMLInputElement).value;
            state.page = 0;
            void load$();
          }}
        />
        <select
          class="sv-select"
          aria-label={m(props.i18n, "ui.field.language")}
          value={state.language}
          onChange$={(event: Event) => {
            state.language = (event.target as HTMLInputElement).value;
            state.page = 0;
            void load$();
          }}
        >
          <option value="">{m(props.i18n, "ui.messages.filter.all-languages")}</option>
          {SUPPORTED_LANGUAGES.map((code) => <option key={code} value={code}>{code}</option>)}
        </select>
        <button
          type="button"
          class="sv-button sv-button--ghost"
          onClick$={() => {
            state.q = "";
            state.language = "";
            state.page = 0;
            void load$();
          }}
        >
          {m(props.i18n, "ui.action.clear-filters")}
        </button>
        <button type="button" class="sv-button sv-button--primary" onClick$={openCreate$}>
          {m(props.i18n, "ui.action.add")}
        </button>
      </div>

      {state.loading && !state.messages ? (
        <div class="sv-loading"><span class="sv-spinner" /></div>
      ) : state.error ? (
        <div class="sv-alert sv-alert--danger" role="alert">{state.error}</div>
      ) : !state.messages || state.messages.items.length === 0 ? (
        <div class="sv-empty">{m(props.i18n, "ui.messages.empty")}</div>
      ) : (
        <>
          <div class="sv-table-wrap">
            <table class="sv-table">
              <thead>
                <tr>
                  <th scope="col">{m(props.i18n, "ui.field.key")}</th>
                  <th scope="col">{m(props.i18n, "ui.field.language")}</th>
                  <th scope="col">{m(props.i18n, "ui.field.text")}</th>
                  <th scope="col"><span class="sv-visually-hidden">{m(props.i18n, "ui.field.actions")}</span></th>
                </tr>
              </thead>
              <tbody>
                {state.messages.items.map((message) => (
                  <tr key={message.id} data-ctx={`message:${message.id}`}>
                    <td class="sv-cell-mono">{message.key}</td>
                    <td><span class="sv-badge">{message.language}</span></td>
                    <td>{message.text}</td>
                    <td class="sv-cell-actions">
                      <button type="button" class="sv-button sv-button--ghost sv-button--sm" onClick$={() => openEdit$(message)}>
                        {m(props.i18n, "ui.action.edit")}
                      </button>
                      <button type="button" class="sv-button sv-button--ghost sv-button--sm" onClick$={() => (state.deleting = message)}>
                        {m(props.i18n, "ui.action.delete")}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pagination
            i18n={props.i18n}
            page={state.page}
            totalPages={state.messages.totalPages}
            onPage$={(next) => {
              state.page = next;
              void load$();
            }}
          />
        </>
      )}

      {state.editing ? (
        <Dialog
          title={m(props.i18n, state.editing === "new" ? "ui.messages.dialog.add" : "ui.messages.dialog.edit")}
          ctx={state.editing !== "new" ? `message:${state.editing.id}` : undefined}
          onClose$={() => (state.editing = null)}
        >
          <form
            class="sv-form"
            onSubmit$={async (event: Event) => {
              event.preventDefault();
              formError.value = undefined;
              const body: MessageInput = {
                key: key.value,
                language: messageLanguage.value,
                text: text.value,
                ...(description.value ? { description: description.value } : {}),
              };
              try {
                if (state.editing === "new") {
                  await api<Message>("/api/v1/messages", { method: "POST", ...jsonBody(body) });
                  await props.toast$(m(props.i18n, "ui.toast.message-created"));
                } else if (state.editing) {
                  await api<Message>(`/api/v1/messages/${state.editing.id}`, {
                    method: "PUT",
                    ...jsonBody(body),
                  });
                  await props.toast$(m(props.i18n, "ui.toast.message-updated"));
                }
                state.editing = null;
                await refreshBundle$();
                await load$();
              } catch (caught) {
                formError.value = caught;
              }
            }}
          >
            <Field label={m(props.i18n, "ui.field.key")} error={fieldErrorFor(formError.value, "key")}>
              <input class="sv-input" value={key.value} onInput$={(event: Event) => (key.value = (event.target as HTMLInputElement).value)} />
            </Field>
            <Field label={m(props.i18n, "ui.field.language")} error={fieldErrorFor(formError.value, "language")}>
              <select class="sv-select" value={messageLanguage.value} onChange$={(event: Event) => (messageLanguage.value = (event.target as HTMLInputElement).value)}>
                {!SUPPORTED_LANGUAGES.includes(messageLanguage.value as "en" | "pl") ? (
                  <option value={messageLanguage.value}>{messageLanguage.value}</option>
                ) : null}
                {SUPPORTED_LANGUAGES.map((code) => <option key={code} value={code}>{code}</option>)}
              </select>
            </Field>
            <Field label={m(props.i18n, "ui.field.text")} error={fieldErrorFor(formError.value, "text")}>
              <textarea class="sv-textarea" value={text.value} onInput$={(event: Event) => (text.value = (event.target as HTMLInputElement).value)} />
            </Field>
            <Field label={m(props.i18n, "ui.field.description")} error={fieldErrorFor(formError.value, "description")}>
              <textarea class="sv-textarea" value={description.value} onInput$={(event: Event) => (description.value = (event.target as HTMLInputElement).value)} />
            </Field>
            {formError.value instanceof ApiError && formError.value.status === 409 ? (
              <div class="sv-alert sv-alert--warning" role="alert">{formError.value.message}</div>
            ) : null}
            <div class="sv-form-actions">
              <button type="button" class="sv-button" onClick$={() => (state.editing = null)}>
                {m(props.i18n, "ui.action.cancel")}
              </button>
              <button type="submit" class="sv-button sv-button--primary">
                {m(props.i18n, "ui.action.save")}
              </button>
            </div>
          </form>
        </Dialog>
      ) : null}

      {state.deleting ? (
        <ConfirmDialog
          title={`${m(props.i18n, "ui.action.delete")} - ${state.deleting.key}`}
          body={m(props.i18n, "ui.confirm.delete-message")}
          ctx={`message:${state.deleting.id}`}
          confirmLabel={m(props.i18n, "ui.action.delete")}
          cancelLabel={m(props.i18n, "ui.action.cancel")}
          onConfirm$={async () => {
            if (!state.deleting) return;
            await api<void>(`/api/v1/messages/${state.deleting.id}`, { method: "DELETE" });
            state.deleting = null;
            await props.toast$(m(props.i18n, "ui.toast.message-deleted"));
            await refreshBundle$();
            await load$();
          }}
          onClose$={() => (state.deleting = null)}
        />
      ) : null}
    </>
  );
});

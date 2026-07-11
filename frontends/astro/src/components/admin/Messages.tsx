import { createSignal, For, onMount, Show } from "solid-js";
import ConfirmDialog from "../ConfirmDialog";
import Dialog from "../Dialog";
import Field from "../Field";
import Pagination from "../Pagination";
import { ApiError, api, fieldErrorFor, jsonBody, queryString } from "../../lib/api";
import { i18n, m, refreshBundle, SUPPORTED_LANGUAGES } from "../../lib/i18n";
import type { Message, MessageInput, Page } from "../../lib/types";
import ClientPage from "../ClientPage";

interface Props {
  toast: (message: string, tone?: "success" | "danger") => void;
}

export function MessagesContent(props: Props) {
  const [q, setQ] = createSignal("");
  const [language, setLanguage] = createSignal("");
  const [page, setPage] = createSignal(0);
  const [messages, setMessages] = createSignal<Page<Message> | null>(null);
  const [loading, setLoading] = createSignal(true);
  const [error, setError] = createSignal<Error | null>(null);
  const [editing, setEditing] = createSignal<Message | "new" | null>(null);
  const [deleting, setDeleting] = createSignal<Message | null>(null);
  const [key, setKey] = createSignal("");
  const [messageLanguage, setMessageLanguage] = createSignal("en");
  const [text, setText] = createSignal("");
  const [description, setDescription] = createSignal("");
  const [formError, setFormError] = createSignal<unknown>(undefined);
  const [savePending, setSavePending] = createSignal(false);
  const [deletePending, setDeletePending] = createSignal(false);
  let loadRequest = 0;

  async function load() {
    const request = ++loadRequest;
    setLoading(true);
    setError(null);
    try {
      const nextMessages = await api<Page<Message>>(`/api/v1/messages${queryString({ q: q(), language: language(), page: page() })}`);
      if (request !== loadRequest) return;
      if (nextMessages.items.length === 0 && page() > 0) {
        const previousPage = Math.max(0, nextMessages.totalPages - 1);
        if (previousPage < page()) {
          setPage(previousPage);
          await load();
          return;
        }
      }
      setMessages(nextMessages);
    } catch (caught) {
      if (request === loadRequest) {
        setError(caught instanceof Error ? caught : new Error(String(caught)));
      }
    } finally {
      if (request === loadRequest) setLoading(false);
    }
  }

  function openCreate() {
    setEditing("new");
    setKey("");
    setMessageLanguage("en");
    setText("");
    setDescription("");
    setFormError(undefined);
  }

  function openEdit(message: Message) {
    setEditing(message);
    setKey(message.key);
    setMessageLanguage(message.language);
    setText(message.text);
    setDescription(message.description ?? "");
    setFormError(undefined);
  }

  async function save() {
    if (savePending()) return;
    setSavePending(true);
    setFormError(undefined);
    const body: MessageInput = {
      key: key(),
      language: messageLanguage(),
      text: text(),
      ...(description() ? { description: description() } : {}),
    };
    try {
      const current = editing();
      if (current === "new") {
        await api<Message>("/api/v1/messages", { method: "POST", ...jsonBody(body) });
        props.toast(m(i18n(), "ui.toast.message-created"));
      } else if (current) {
        await api<Message>(`/api/v1/messages/${current.id}`, {
          method: "PUT",
          ...jsonBody(body),
        });
        props.toast(m(i18n(), "ui.toast.message-updated"));
      }
      setEditing(null);
      await refreshBundle();
      await load();
    } catch (caught) {
      setFormError(caught);
    } finally {
      setSavePending(false);
    }
  }

  async function remove(message: Message) {
    if (deletePending()) return;
    setDeletePending(true);
    try {
      await api<void>(`/api/v1/messages/${message.id}`, { method: "DELETE" });
      props.toast(m(i18n(), "ui.toast.message-deleted"));
      await refreshBundle();
      await load();
    } catch (caught) {
      props.toast(caught instanceof Error ? caught.message : String(caught), "danger");
    } finally {
      setDeletePending(false);
      setDeleting(null);
    }
  }

  function clearFilters() {
    setQ("");
    setLanguage("");
    setPage(0);
    void load();
  }

  function submit(event: SubmitEvent) {
    event.preventDefault();
    void save();
  }

  onMount(() => {
    void load();
  });

  return (
    <>
      <h1 class="sv-page-title">{m(i18n(), "ui.admin.messages")}</h1>
      <div class="sv-toolbar">
        <input
          class="sv-input"
          placeholder={m(i18n(), "ui.messages.search.placeholder")}
          aria-label={m(i18n(), "ui.messages.search.placeholder")}
          value={q()}
          onInput={(event) => {
            setQ(event.currentTarget.value);
            setPage(0);
            void load();
          }}
        />
        <select
          class="sv-select"
          aria-label={m(i18n(), "ui.field.language")}
          value={language()}
          onChange={(event) => {
            setLanguage(event.currentTarget.value);
            setPage(0);
            void load();
          }}
        >
          <option value="">{m(i18n(), "ui.messages.filter.all-languages")}</option>
          <For each={SUPPORTED_LANGUAGES}>{(code) => <option value={code}>{code}</option>}</For>
        </select>
        <button type="button" class="sv-button sv-button--ghost" onClick={clearFilters}>
          {m(i18n(), "ui.action.clear-filters")}
        </button>
        <button type="button" class="sv-button sv-button--primary" onClick={openCreate}>
          {m(i18n(), "ui.action.add")}
        </button>
      </div>

      {loading() && !messages() ? (
        <div class="sv-loading"><span class="sv-spinner" /></div>
      ) : error() ? (
        <div class="sv-alert sv-alert--danger" role="alert">{error()?.message}</div>
      ) : !messages() || messages()!.items.length === 0 ? (
        <div class="sv-empty">{m(i18n(), "ui.messages.empty")}</div>
      ) : (
        <>
          <div class="sv-table-wrap">
            <table class="sv-table">
              <thead>
                <tr>
                  <th scope="col">{m(i18n(), "ui.field.key")}</th>
                  <th scope="col">{m(i18n(), "ui.field.language")}</th>
                  <th scope="col">{m(i18n(), "ui.field.text")}</th>
                  <th scope="col"><span class="sv-visually-hidden">{m(i18n(), "ui.field.actions")}</span></th>
                </tr>
              </thead>
              <tbody>
                <For each={messages()!.items}>
                  {(message) => (
                    <tr data-ctx={`message:${message.id}`}>
                      <td class="sv-cell-mono">{message.key}</td>
                      <td><span class="sv-badge">{message.language}</span></td>
                      <td>{message.text}</td>
                      <td class="sv-cell-actions">
                        <button type="button" class="sv-button sv-button--ghost sv-button--sm" onClick={() => openEdit(message)}>
                          {m(i18n(), "ui.action.edit")}
                        </button>
                        <button type="button" class="sv-button sv-button--ghost sv-button--sm" onClick={() => setDeleting(message)}>
                          {m(i18n(), "ui.action.delete")}
                        </button>
                      </td>
                    </tr>
                  )}
                </For>
              </tbody>
            </table>
          </div>
          <Pagination page={page()} totalPages={messages()!.totalPages} onPage={(next) => {
            setPage(next);
            void load();
          }} />
        </>
      )}

      <Show when={editing()}>
        {(current) => {
          const item = current();
          return (
            <Dialog
              title={m(i18n(), item === "new" ? "ui.messages.dialog.add" : "ui.messages.dialog.edit")}
              ctx={item !== "new" ? `message:${item.id}` : undefined}
              onClose={() => setEditing(null)}
            >
              <form class="sv-form" onSubmit={submit}>
              <Field label={m(i18n(), "ui.field.key")} error={fieldErrorFor(formError(), "key")}>
                <input class="sv-input" value={key()} onInput={(event) => setKey(event.currentTarget.value)} />
              </Field>
              <Field label={m(i18n(), "ui.field.language")} error={fieldErrorFor(formError(), "language")}>
                <select class="sv-select" value={messageLanguage()} onChange={(event) => setMessageLanguage(event.currentTarget.value)}>
                  <Show when={!SUPPORTED_LANGUAGES.includes(messageLanguage() as "en" | "pl")}>
                    <option value={messageLanguage()}>{messageLanguage()}</option>
                  </Show>
                  <For each={SUPPORTED_LANGUAGES}>{(code) => <option value={code}>{code}</option>}</For>
                </select>
              </Field>
              <Field label={m(i18n(), "ui.field.text")} error={fieldErrorFor(formError(), "text")}>
                <textarea class="sv-textarea" value={text()} onInput={(event) => setText(event.currentTarget.value)} />
              </Field>
              <Field label={m(i18n(), "ui.field.description")} error={fieldErrorFor(formError(), "description")}>
                <textarea class="sv-textarea" value={description()} onInput={(event) => setDescription(event.currentTarget.value)} />
              </Field>
              <Show when={formError() instanceof ApiError && (formError() as ApiError).status === 409}>
                <div class="sv-alert sv-alert--warning" role="alert">{(formError() as ApiError).message}</div>
              </Show>
              <div class="sv-form-actions">
                <button type="button" class="sv-button" onClick={() => setEditing(null)}>
                  {m(i18n(), "ui.action.cancel")}
                </button>
                <button type="submit" class="sv-button sv-button--primary" disabled={savePending()}>
                  {m(i18n(), "ui.action.save")}
                </button>
              </div>
              </form>
            </Dialog>
          );
        }}
      </Show>

      <Show when={deleting()}>
        {(message) => (
          <ConfirmDialog
            title={`${m(i18n(), "ui.action.delete")} - ${message().key}`}
            body={m(i18n(), "ui.confirm.delete-message")}
            ctx={`message:${message().id}`}
            confirmLabel={m(i18n(), "ui.action.delete")}
            cancelLabel={m(i18n(), "ui.action.cancel")}
            pending={deletePending()}
            onConfirm={() => remove(message())}
            onClose={() => setDeleting(null)}
          />
        )}
      </Show>
    </>
  );
}

export default function Messages() {
  return <ClientPage requiredRole="admin">{(toast) => <MessagesContent toast={toast} />}</ClientPage>;
}

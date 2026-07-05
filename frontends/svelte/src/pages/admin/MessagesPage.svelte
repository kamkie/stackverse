<script lang="ts">
  import { onMount } from "svelte";
  import { ApiError, api, fieldErrorFor, jsonBody, queryString } from "../../lib/api";
  import { i18n, m, refreshBundle, SUPPORTED_LANGUAGES } from "../../lib/i18n";
  import type { Message, MessageInput, Page } from "../../lib/types";
  import ConfirmDialog from "../../components/ConfirmDialog.svelte";
  import Dialog from "../../components/Dialog.svelte";
  import Field from "../../components/Field.svelte";
  import Pagination from "../../components/Pagination.svelte";

  export let toast: (message: string, tone?: "success" | "danger") => void;

  let q = "";
  let language = "";
  let page = 0;
  let messages: Page<Message> | null = null;
  let loading = true;
  let error: Error | null = null;
  let editing: Message | null | "new" = null;
  let deleting: Message | null = null;
  let key = "";
  let messageLanguage = "en";
  let text = "";
  let description = "";
  let formError: unknown = undefined;

  async function load() {
    loading = true;
    error = null;
    try {
      messages = await api<Page<Message>>(
        `/api/v1/messages${queryString({ q, language, page })}`,
      );
    } catch (caught) {
      error = caught instanceof Error ? caught : new Error(String(caught));
    } finally {
      loading = false;
    }
  }

  function openCreate() {
    editing = "new";
    key = "";
    messageLanguage = "en";
    text = "";
    description = "";
    formError = undefined;
  }

  function openEdit(message: Message) {
    editing = message;
    key = message.key;
    messageLanguage = message.language;
    text = message.text;
    description = message.description ?? "";
    formError = undefined;
  }

  async function save() {
    formError = undefined;
    const body: MessageInput = {
      key,
      language: messageLanguage,
      text,
      ...(description ? { description } : {}),
    };
    try {
      if (editing === "new") {
        await api<Message>("/api/v1/messages", { method: "POST", ...jsonBody(body) });
        toast(m($i18n, "ui.toast.message-created"));
      } else if (editing) {
        await api<Message>(`/api/v1/messages/${editing.id}`, {
          method: "PUT",
          ...jsonBody(body),
        });
        toast(m($i18n, "ui.toast.message-updated"));
      }
      editing = null;
      await refreshBundle();
      await load();
    } catch (caught) {
      formError = caught;
    }
  }

  async function remove(message: Message) {
    await api<void>(`/api/v1/messages/${message.id}`, { method: "DELETE" });
    deleting = null;
    toast(m($i18n, "ui.toast.message-deleted"));
    await refreshBundle();
    await load();
  }

  function clearFilters() {
    q = "";
    language = "";
    page = 0;
    void load();
  }

  onMount(() => {
    void load();
  });
</script>

<h1 class="sv-page-title">{m($i18n, "ui.admin.messages")}</h1>
<div class="sv-toolbar">
  <input
    class="sv-input"
    placeholder={m($i18n, "ui.messages.search.placeholder")}
    aria-label={m($i18n, "ui.messages.search.placeholder")}
    bind:value={q}
    on:input={() => {
      page = 0;
      void load();
    }}
  />
  <select
    class="sv-select"
    aria-label={m($i18n, "ui.field.language")}
    bind:value={language}
    on:change={() => {
      page = 0;
      void load();
    }}
  >
    <option value="">{m($i18n, "ui.messages.filter.all-languages")}</option>
    {#each SUPPORTED_LANGUAGES as code}
      <option value={code}>{code}</option>
    {/each}
  </select>
  <button type="button" class="sv-button sv-button--ghost" on:click={clearFilters}>
    {m($i18n, "ui.action.clear-filters")}
  </button>
  <button type="button" class="sv-button sv-button--primary" on:click={openCreate}>
    {m($i18n, "ui.action.add")}
  </button>
</div>

{#if loading && !messages}
  <div class="sv-loading"><span class="sv-spinner"></span></div>
{:else if error}
  <div class="sv-alert sv-alert--danger" role="alert">{error.message}</div>
{:else if !messages || messages.items.length === 0}
  <div class="sv-empty">{m($i18n, "ui.messages.empty")}</div>
{:else}
  <div class="sv-table-wrap">
    <table class="sv-table">
      <thead>
        <tr>
          <th scope="col">{m($i18n, "ui.field.key")}</th>
          <th scope="col">{m($i18n, "ui.field.language")}</th>
          <th scope="col">{m($i18n, "ui.field.text")}</th>
          <th scope="col"><span class="sv-visually-hidden">{m($i18n, "ui.field.actions")}</span></th>
        </tr>
      </thead>
      <tbody>
        {#each messages.items as message (message.id)}
          <tr data-ctx={`message:${message.id}`}>
            <td class="sv-cell-mono">{message.key}</td>
            <td><span class="sv-badge">{message.language}</span></td>
            <td>{message.text}</td>
            <td class="sv-cell-actions">
              <button type="button" class="sv-button sv-button--ghost sv-button--sm" on:click={() => openEdit(message)}>
                {m($i18n, "ui.action.edit")}
              </button>
              <button type="button" class="sv-button sv-button--ghost sv-button--sm" on:click={() => (deleting = message)}>
                {m($i18n, "ui.action.delete")}
              </button>
            </td>
          </tr>
        {/each}
      </tbody>
    </table>
  </div>
  <Pagination
    {page}
    totalPages={messages.totalPages}
    onPage={(next) => {
      page = next;
      void load();
    }}
  />
{/if}

{#if editing}
  <Dialog
    title={m($i18n, editing === "new" ? "ui.messages.dialog.add" : "ui.messages.dialog.edit")}
    ctx={editing !== "new" ? `message:${editing.id}` : undefined}
    on:close={() => (editing = null)}
  >
    <form class="sv-form" on:submit|preventDefault={save}>
      <Field label={m($i18n, "ui.field.key")} error={fieldErrorFor(formError, "key")}>
        <input class="sv-input" bind:value={key} />
      </Field>
      <Field label={m($i18n, "ui.field.language")} error={fieldErrorFor(formError, "language")}>
        <select class="sv-select" bind:value={messageLanguage}>
          {#if !SUPPORTED_LANGUAGES.includes(messageLanguage as "en" | "pl")}
            <option value={messageLanguage}>{messageLanguage}</option>
          {/if}
          {#each SUPPORTED_LANGUAGES as code}
            <option value={code}>{code}</option>
          {/each}
        </select>
      </Field>
      <Field label={m($i18n, "ui.field.text")} error={fieldErrorFor(formError, "text")}>
        <textarea class="sv-textarea" bind:value={text}></textarea>
      </Field>
      <Field label={m($i18n, "ui.field.description")} error={fieldErrorFor(formError, "description")}>
        <textarea class="sv-textarea" bind:value={description}></textarea>
      </Field>
      {#if formError instanceof ApiError && formError.status === 409}
        <div class="sv-alert sv-alert--warning" role="alert">{formError.message}</div>
      {/if}
      <div class="sv-form-actions">
        <button type="button" class="sv-button" on:click={() => (editing = null)}>
          {m($i18n, "ui.action.cancel")}
        </button>
        <button type="submit" class="sv-button sv-button--primary">
          {m($i18n, "ui.action.save")}
        </button>
      </div>
    </form>
  </Dialog>
{/if}

{#if deleting}
  <ConfirmDialog
    title={`${m($i18n, "ui.action.delete")} - ${deleting.key}`}
    body={m($i18n, "ui.confirm.delete-message")}
    ctx={`message:${deleting.id}`}
    confirmLabel={m($i18n, "ui.action.delete")}
    cancelLabel={m($i18n, "ui.action.cancel")}
    onConfirm={() => remove(deleting as Message)}
    onClose={() => (deleting = null)}
  />
{/if}

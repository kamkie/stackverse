<script lang="ts">
  import { onMount } from "svelte";
  import {
    ApiError,
    api,
    fieldErrorFor,
    jsonBody,
    queryString,
  } from "../../lib/api";
  import { i18n, m, refreshBundle, SUPPORTED_LANGUAGES } from "../../lib/i18n";
  import type { Message, MessageInput, Page } from "../../lib/types";
  import ConfirmDialog from "../../components/ConfirmDialog.svelte";
  import Dialog from "../../components/Dialog.svelte";
  import Field from "../../components/Field.svelte";
  import Pagination from "../../components/Pagination.svelte";
  import { fromStore } from "svelte/store";

  let {
    toast,
  }: { toast: (message: string, tone?: "success" | "danger") => void } =
    $props();

  let q = $state("");
  let language = $state("");
  let page = $state(0);
  let messages: Page<Message> | null = $state(null);
  let loading = $state(true);
  let error: Error | null = $state(null);
  let editing: Message | null | "new" = $state(null);
  let deleting: Message | null = $state(null);
  let key = $state("");
  let messageLanguage = $state("en");
  let text = $state("");
  let description = $state("");
  let formError: unknown = $state(undefined);
  const i18nState = fromStore(i18n);

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
        await api<Message>("/api/v1/messages", {
          method: "POST",
          ...jsonBody(body),
        });
        toast(m(i18nState.current, "ui.toast.message-created"));
      } else if (editing) {
        await api<Message>(`/api/v1/messages/${editing.id}`, {
          method: "PUT",
          ...jsonBody(body),
        });
        toast(m(i18nState.current, "ui.toast.message-updated"));
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
    toast(m(i18nState.current, "ui.toast.message-deleted"));
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

  function submit(event: SubmitEvent) {
    event.preventDefault();
    void save();
  }
</script>

<h1 class="sv-page-title">{m(i18nState.current, "ui.admin.messages")}</h1>
<div class="sv-toolbar">
  <input
    class="sv-input"
    placeholder={m(i18nState.current, "ui.messages.search.placeholder")}
    aria-label={m(i18nState.current, "ui.messages.search.placeholder")}
    bind:value={q}
    oninput={() => {
      page = 0;
      void load();
    }}
  />
  <select
    class="sv-select"
    aria-label={m(i18nState.current, "ui.field.language")}
    bind:value={language}
    onchange={() => {
      page = 0;
      void load();
    }}
  >
    <option value=""
      >{m(i18nState.current, "ui.messages.filter.all-languages")}</option
    >
    {#each SUPPORTED_LANGUAGES as code (code)}
      <option value={code}>{code}</option>
    {/each}
  </select>
  <button
    type="button"
    class="sv-button sv-button--ghost"
    onclick={clearFilters}
  >
    {m(i18nState.current, "ui.action.clear-filters")}
  </button>
  <button
    type="button"
    class="sv-button sv-button--primary"
    onclick={openCreate}
  >
    {m(i18nState.current, "ui.action.add")}
  </button>
</div>

{#if loading && !messages}
  <div class="sv-loading"><span class="sv-spinner"></span></div>
{:else if error}
  <div class="sv-alert sv-alert--danger" role="alert">{error.message}</div>
{:else if !messages || messages.items.length === 0}
  <div class="sv-empty">{m(i18nState.current, "ui.messages.empty")}</div>
{:else}
  <div class="sv-table-wrap">
    <table class="sv-table">
      <thead>
        <tr>
          <th scope="col">{m(i18nState.current, "ui.field.key")}</th>
          <th scope="col">{m(i18nState.current, "ui.field.language")}</th>
          <th scope="col">{m(i18nState.current, "ui.field.text")}</th>
          <th scope="col"
            ><span class="sv-visually-hidden"
              >{m(i18nState.current, "ui.field.actions")}</span
            ></th
          >
        </tr>
      </thead>
      <tbody>
        {#each messages.items as message (message.id)}
          <tr data-ctx={`message:${message.id}`}>
            <td class="sv-cell-mono">{message.key}</td>
            <td><span class="sv-badge">{message.language}</span></td>
            <td>{message.text}</td>
            <td class="sv-cell-actions">
              <button
                type="button"
                class="sv-button sv-button--ghost sv-button--sm"
                onclick={() => openEdit(message)}
              >
                {m(i18nState.current, "ui.action.edit")}
              </button>
              <button
                type="button"
                class="sv-button sv-button--ghost sv-button--sm"
                onclick={() => (deleting = message)}
              >
                {m(i18nState.current, "ui.action.delete")}
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
    title={m(
      i18nState.current,
      editing === "new" ? "ui.messages.dialog.add" : "ui.messages.dialog.edit",
    )}
    ctx={editing !== "new" ? `message:${editing.id}` : undefined}
    onClose={() => (editing = null)}
  >
    <form class="sv-form" onsubmit={submit}>
      <Field
        label={m(i18nState.current, "ui.field.key")}
        error={fieldErrorFor(formError, "key")}
      >
        <input class="sv-input" bind:value={key} />
      </Field>
      <Field
        label={m(i18nState.current, "ui.field.language")}
        error={fieldErrorFor(formError, "language")}
      >
        <select class="sv-select" bind:value={messageLanguage}>
          {#if !SUPPORTED_LANGUAGES.includes(messageLanguage as "en" | "pl")}
            <option value={messageLanguage}>{messageLanguage}</option>
          {/if}
          {#each SUPPORTED_LANGUAGES as code (code)}
            <option value={code}>{code}</option>
          {/each}
        </select>
      </Field>
      <Field
        label={m(i18nState.current, "ui.field.text")}
        error={fieldErrorFor(formError, "text")}
      >
        <textarea class="sv-textarea" bind:value={text}></textarea>
      </Field>
      <Field
        label={m(i18nState.current, "ui.field.description")}
        error={fieldErrorFor(formError, "description")}
      >
        <textarea class="sv-textarea" bind:value={description}></textarea>
      </Field>
      {#if formError instanceof ApiError && formError.status === 409}
        <div class="sv-alert sv-alert--warning" role="alert">
          {formError.message}
        </div>
      {/if}
      <div class="sv-form-actions">
        <button
          type="button"
          class="sv-button"
          onclick={() => (editing = null)}
        >
          {m(i18nState.current, "ui.action.cancel")}
        </button>
        <button type="submit" class="sv-button sv-button--primary">
          {m(i18nState.current, "ui.action.save")}
        </button>
      </div>
    </form>
  </Dialog>
{/if}

{#if deleting}
  <ConfirmDialog
    title={`${m(i18nState.current, "ui.action.delete")} - ${deleting.key}`}
    body={m(i18nState.current, "ui.confirm.delete-message")}
    ctx={`message:${deleting.id}`}
    confirmLabel={m(i18nState.current, "ui.action.delete")}
    cancelLabel={m(i18nState.current, "ui.action.cancel")}
    onConfirm={() => remove(deleting as Message)}
    onClose={() => (deleting = null)}
  />
{/if}

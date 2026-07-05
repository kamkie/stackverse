<script lang="ts">
  import { onMount } from "svelte";
  import { api, fieldErrorFor, jsonBody, queryString } from "../../lib/api";
  import { formatDate } from "../../lib/format";
  import { i18n, m } from "../../lib/i18n";
  import { me } from "../../lib/session";
  import type { Page, UserAccount } from "../../lib/types";
  import Dialog from "../../components/Dialog.svelte";
  import Field from "../../components/Field.svelte";
  import Pagination from "../../components/Pagination.svelte";

  let q = "";
  let page = 0;
  let users: Page<UserAccount> | null = null;
  let loading = true;
  let error: Error | null = null;
  let blocking: UserAccount | null = null;
  let reason = "";
  let blockError: unknown = undefined;
  let blockReasonError: string | undefined = undefined;

  async function load() {
    loading = true;
    error = null;
    try {
      users = await api<Page<UserAccount>>(
        `/api/v1/admin/users${queryString({ q, page })}`,
      );
    } catch (caught) {
      error = caught instanceof Error ? caught : new Error(String(caught));
    } finally {
      loading = false;
    }
  }

  function openBlock(user: UserAccount) {
    blocking = user;
    reason = "";
    blockError = undefined;
    blockReasonError = undefined;
  }

  async function setStatus(username: string, status: "active" | "blocked") {
    if (status === "blocked" && reason.trim() === "") {
      blockReasonError = m($i18n, "validation.block.reason.required");
      return;
    }
    blockError = undefined;
    blockReasonError = undefined;
    try {
      await api<UserAccount>(`/api/v1/admin/users/${encodeURIComponent(username)}/status`, {
        method: "PUT",
        ...jsonBody({ status, ...(status === "blocked" ? { reason } : {}) }),
      });
      blocking = null;
      await load();
    } catch (caught) {
      blockError = caught;
    }
  }

  onMount(() => {
    void load();
  });
</script>

<h1 class="sv-page-title">{m($i18n, "ui.admin.users")}</h1>
<div class="sv-toolbar">
  <input
    type="search"
    class="sv-input"
    placeholder={m($i18n, "ui.users.search.placeholder")}
    bind:value={q}
    on:input={() => {
      page = 0;
      void load();
    }}
  />
</div>

{#if loading && !users}
  <div class="sv-loading"><span class="sv-spinner"></span></div>
{:else if error}
  <div class="sv-alert sv-alert--danger" role="alert">{error.message}</div>
{:else if users}
  <div class="sv-table-wrap">
    <table class="sv-table">
      <thead>
        <tr>
          <th scope="col">{m($i18n, "ui.field.username")}</th>
          <th scope="col">{m($i18n, "ui.field.last-seen")}</th>
          <th scope="col">{m($i18n, "ui.field.bookmarks")}</th>
          <th scope="col">{m($i18n, "ui.field.status")}</th>
          <th scope="col"><span class="sv-visually-hidden">{m($i18n, "ui.field.actions")}</span></th>
        </tr>
      </thead>
      <tbody>
        {#each users.items as user (user.username)}
          <tr data-ctx={`user:${user.username}`}>
            <td>{user.username}</td>
            <td><time dateTime={user.lastSeen}>{formatDate(user.lastSeen, $i18n.resolvedLanguage)}</time></td>
            <td>{user.bookmarkCount}</td>
            <td>
              {#if user.status === "blocked"}
                <span class="sv-badge sv-badge--danger" title={user.blockedReason}>{m($i18n, "ui.user.status.blocked")}</span>
              {:else}
                <span class="sv-badge sv-badge--success">{m($i18n, "ui.user.status.active")}</span>
              {/if}
            </td>
            <td class="sv-cell-actions">
              {#if user.status === "blocked"}
                <button type="button" class="sv-button sv-button--sm" on:click={() => setStatus(user.username, "active")}>
                  {m($i18n, "ui.action.unblock")}
                </button>
              {:else if $me && $me.username !== user.username}
                <button type="button" class="sv-button sv-button--sm" on:click={() => openBlock(user)}>
                  {m($i18n, "ui.action.block")}
                </button>
              {/if}
            </td>
          </tr>
        {/each}
      </tbody>
    </table>
  </div>
  <Pagination
    {page}
    totalPages={users.totalPages}
    onPage={(next) => {
      page = next;
      void load();
    }}
  />
{/if}

{#if blocking}
  <Dialog title={`${m($i18n, "ui.action.block")} - ${blocking.username}`} ctx={`user:${blocking.username}`} on:close={() => (blocking = null)}>
    <form class="sv-form" on:submit|preventDefault={() => setStatus((blocking as UserAccount).username, "blocked")}>
      <Field label={m($i18n, "ui.field.reason")} error={fieldErrorFor(blockError, "reason") ?? blockReasonError}>
        <textarea class="sv-textarea" bind:value={reason}></textarea>
      </Field>
      <div class="sv-form-actions">
        <button type="button" class="sv-button" on:click={() => (blocking = null)}>
          {m($i18n, "ui.action.cancel")}
        </button>
        <button type="submit" class="sv-button sv-button--danger">
          {m($i18n, "ui.action.block")}
        </button>
      </div>
    </form>
  </Dialog>
{/if}

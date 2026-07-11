import { createSignal, For, onMount, Show } from "solid-js";
import Dialog from "../Dialog";
import Field from "../Field";
import Pagination from "../Pagination";
import { api, fieldErrorFor, jsonBody, queryString } from "../../lib/api";
import { formatDate } from "../../lib/format";
import { i18n, m } from "../../lib/i18n";
import { me } from "../../lib/session";
import type { Page, UserAccount } from "../../lib/types";
import ClientPage from "../ClientPage";

export function UsersContent() {
  const [q, setQ] = createSignal("");
  const [page, setPage] = createSignal(0);
  const [users, setUsers] = createSignal<Page<UserAccount> | null>(null);
  const [loading, setLoading] = createSignal(true);
  const [error, setError] = createSignal<Error | null>(null);
  const [blocking, setBlocking] = createSignal<UserAccount | null>(null);
  const [reason, setReason] = createSignal("");
  const [blockError, setBlockError] = createSignal<unknown>(undefined);
  const [blockReasonError, setBlockReasonError] = createSignal<string | undefined>(undefined);

  async function load() {
    setLoading(true);
    setError(null);
    try {
      setUsers(await api<Page<UserAccount>>(`/api/v1/admin/users${queryString({ q: q(), page: page() })}`));
    } catch (caught) {
      setError(caught instanceof Error ? caught : new Error(String(caught)));
    } finally {
      setLoading(false);
    }
  }

  function openBlock(user: UserAccount) {
    setBlocking(user);
    setReason("");
    setBlockError(undefined);
    setBlockReasonError(undefined);
  }

  async function setUserStatus(username: string, status: "active" | "blocked") {
    if (status === "blocked" && reason().trim() === "") {
      setBlockReasonError(m(i18n(), "validation.block.reason.required"));
      return;
    }
    setBlockError(undefined);
    setBlockReasonError(undefined);
    try {
      await api<UserAccount>(`/api/v1/admin/users/${encodeURIComponent(username)}/status`, {
        method: "PUT",
        ...jsonBody({ status, ...(status === "blocked" ? { reason: reason() } : {}) }),
      });
      setBlocking(null);
      await load();
    } catch (caught) {
      setBlockError(caught);
    }
  }

  function submitBlock(event: SubmitEvent) {
    event.preventDefault();
    const user = blocking();
    if (user) void setUserStatus(user.username, "blocked");
  }

  onMount(() => {
    void load();
  });

  return (
    <>
      <h1 class="sv-page-title">{m(i18n(), "ui.admin.users")}</h1>
      <div class="sv-toolbar">
        <input
          type="search"
          class="sv-input"
          placeholder={m(i18n(), "ui.users.search.placeholder")}
          value={q()}
          onInput={(event) => {
            setQ(event.currentTarget.value);
            setPage(0);
            void load();
          }}
        />
      </div>

      {loading() && !users() ? (
        <div class="sv-loading"><span class="sv-spinner" /></div>
      ) : error() ? (
        <div class="sv-alert sv-alert--danger" role="alert">{error()?.message}</div>
      ) : users() ? (
        <>
          <div class="sv-table-wrap">
            <table class="sv-table">
              <thead>
                <tr>
                  <th scope="col">{m(i18n(), "ui.field.username")}</th>
                  <th scope="col">{m(i18n(), "ui.field.last-seen")}</th>
                  <th scope="col">{m(i18n(), "ui.field.bookmarks")}</th>
                  <th scope="col">{m(i18n(), "ui.field.status")}</th>
                  <th scope="col"><span class="sv-visually-hidden">{m(i18n(), "ui.field.actions")}</span></th>
                </tr>
              </thead>
              <tbody>
                <For each={users()!.items}>
                  {(user) => (
                    <tr data-ctx={`user:${user.username}`}>
                      <td>{user.username}</td>
                      <td><time dateTime={user.lastSeen}>{formatDate(user.lastSeen, i18n().resolvedLanguage)}</time></td>
                      <td>{user.bookmarkCount}</td>
                      <td>
                        <Show
                          when={user.status === "blocked"}
                          fallback={<span class="sv-badge sv-badge--success">{m(i18n(), "ui.user.status.active")}</span>}
                        >
                          <span class="sv-badge sv-badge--danger" title={user.blockedReason}>
                            {m(i18n(), "ui.user.status.blocked")}
                          </span>
                        </Show>
                      </td>
                      <td class="sv-cell-actions">
                        <Show
                          when={user.status === "blocked"}
                          fallback={
                            <Show when={me() && me()!.username !== user.username}>
                              <button type="button" class="sv-button sv-button--sm" onClick={() => openBlock(user)}>
                                {m(i18n(), "ui.action.block")}
                              </button>
                            </Show>
                          }
                        >
                          <button type="button" class="sv-button sv-button--sm" onClick={() => setUserStatus(user.username, "active")}>
                            {m(i18n(), "ui.action.unblock")}
                          </button>
                        </Show>
                      </td>
                    </tr>
                  )}
                </For>
              </tbody>
            </table>
          </div>
          <Pagination page={page()} totalPages={users()!.totalPages} onPage={(next) => {
            setPage(next);
            void load();
          }} />
        </>
      ) : null}

      <Show when={blocking()}>
        {(user) => (
          <Dialog title={`${m(i18n(), "ui.action.block")} - ${user().username}`} ctx={`user:${user().username}`} onClose={() => setBlocking(null)}>
            <form class="sv-form" onSubmit={submitBlock}>
              <Field label={m(i18n(), "ui.field.reason")} error={fieldErrorFor(blockError(), "reason") ?? blockReasonError()}>
                <textarea class="sv-textarea" value={reason()} onInput={(event) => setReason(event.currentTarget.value)} />
              </Field>
              <div class="sv-form-actions">
                <button type="button" class="sv-button" onClick={() => setBlocking(null)}>
                  {m(i18n(), "ui.action.cancel")}
                </button>
                <button type="submit" class="sv-button sv-button--danger">
                  {m(i18n(), "ui.action.block")}
                </button>
              </div>
            </form>
          </Dialog>
        )}
      </Show>
    </>
  );
}

export default function Users() {
  return <ClientPage requiredRole="admin">{() => <UsersContent />}</ClientPage>;
}

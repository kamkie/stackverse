import {
  $,
  component$,
  useSignal,
  useStore,
  useVisibleTask$,
} from "@builder.io/qwik";
import Dialog from "../../components/Dialog";
import Field from "../../components/Field";
import Pagination from "../../components/Pagination";
import { api, fieldErrorFor, jsonBody, queryString } from "../../lib/api";
import { formatDate } from "../../lib/format";
import { formText } from "../../lib/form";
import { m, type I18nState } from "../../lib/i18n";
import type { Page, User, UserAccount } from "../../lib/types";

export default component$<{ i18n: I18nState; me: User | null | undefined }>(
  (props) => {
    const state = useStore<{
      q: string;
      page: number;
      users: Page<UserAccount> | null;
      loading: boolean;
      error: string;
      blocking: UserAccount | null;
    }>({
      q: "",
      page: 0,
      users: null,
      loading: true,
      error: "",
      blocking: null,
    });
    const reason = useSignal("");
    const blockError = useSignal<unknown>(undefined);
    const blockReasonError = useSignal("");
    const usersRevision = useSignal(0);

    const load$ = $(async () => {
      state.loading = true;
      state.error = "";
      try {
        state.users = await api<Page<UserAccount>>(
          `/api/v1/admin/users${queryString({ q: state.q, page: state.page })}`,
        );
        usersRevision.value += 1;
      } catch (caught) {
        state.error = caught instanceof Error ? caught.message : String(caught);
      } finally {
        state.loading = false;
      }
    });

    const setUserStatus$ = $(
      async (
        username: string,
        status: "active" | "blocked",
        nextReason?: string,
      ) => {
        const trimmedReason =
          status === "blocked" ? (nextReason ?? "").trim() : "";
        if (status === "blocked" && trimmedReason === "") {
          blockReasonError.value = m(
            props.i18n,
            "validation.block.reason.required",
          );
          return;
        }
        blockError.value = undefined;
        blockReasonError.value = "";
        try {
          const updatedUser = await api<UserAccount>(
            `/api/v1/admin/users/${encodeURIComponent(username)}/status`,
            {
              method: "PUT",
              ...jsonBody({
                status,
                ...(status === "blocked" ? { reason: trimmedReason } : {}),
              }),
            },
          );
          if (status === "active") {
            state.users = null;
            usersRevision.value += 1;
            await load$();
          } else if (state.users) {
            const existingUser = state.users.items.find(
              (user) => user.username === updatedUser.username,
            );
            if (existingUser) {
              existingUser.firstSeen = updatedUser.firstSeen;
              existingUser.lastSeen = updatedUser.lastSeen;
              existingUser.status = updatedUser.status;
              existingUser.blockedReason = updatedUser.blockedReason;
              existingUser.bookmarkCount = updatedUser.bookmarkCount;
              usersRevision.value += 1;
            }
          }
          state.blocking = null;
        } catch (caught) {
          blockError.value = caught;
        }
      },
    );

    useVisibleTask$(() => {
      void load$();
    });

    return (
      <>
        <h1 class="sv-page-title">{m(props.i18n, "ui.admin.users")}</h1>
        <div class="sv-toolbar">
          <input
            type="search"
            class="sv-input"
            placeholder={m(props.i18n, "ui.users.search.placeholder")}
            value={state.q}
            onInput$={(event: Event) => {
              state.q = (event.target as HTMLInputElement).value;
              state.page = 0;
              void load$();
            }}
          />
        </div>

        {state.loading && !state.users ? (
          <div class="sv-loading">
            <span class="sv-spinner" />
          </div>
        ) : state.error ? (
          <div class="sv-alert sv-alert--danger" role="alert">
            {state.error}
          </div>
        ) : state.users ? (
          <>
            <div class="sv-table-wrap">
              <table class="sv-table">
                <thead>
                  <tr>
                    <th scope="col">{m(props.i18n, "ui.field.username")}</th>
                    <th scope="col">{m(props.i18n, "ui.field.last-seen")}</th>
                    <th scope="col">{m(props.i18n, "ui.field.bookmarks")}</th>
                    <th scope="col">{m(props.i18n, "ui.field.status")}</th>
                    <th scope="col">
                      <span class="sv-visually-hidden">
                        {m(props.i18n, "ui.field.actions")}
                      </span>
                    </th>
                  </tr>
                </thead>
                <tbody
                  key={`users:${usersRevision.value}`}
                  data-revision={usersRevision.value}
                >
                  {state.users.items.map((user) => (
                    <tr
                      key={`${user.username}:${user.status}:${user.blockedReason ?? ""}:${usersRevision.value}`}
                      data-ctx={`user:${user.username}`}
                    >
                      <td>{user.username}</td>
                      <td>
                        <time dateTime={user.lastSeen}>
                          {formatDate(
                            user.lastSeen,
                            props.i18n.resolvedLanguage,
                          )}
                        </time>
                      </td>
                      <td>{user.bookmarkCount}</td>
                      <td>
                        {user.status === "blocked" ? (
                          <span
                            class="sv-badge sv-badge--danger"
                            title={user.blockedReason}
                          >
                            {m(props.i18n, "ui.user.status.blocked")}
                          </span>
                        ) : (
                          <span class="sv-badge sv-badge--success">
                            {m(props.i18n, "ui.user.status.active")}
                          </span>
                        )}
                      </td>
                      <td class="sv-cell-actions">
                        {user.status === "blocked" ? (
                          <button
                            type="button"
                            class="sv-button sv-button--sm"
                            onClick$={async () =>
                              setUserStatus$(user.username, "active")
                            }
                          >
                            {m(props.i18n, "ui.action.unblock")}
                          </button>
                        ) : props.me && props.me.username !== user.username ? (
                          <button
                            type="button"
                            class="sv-button sv-button--sm"
                            onClick$={() => {
                              state.blocking = user;
                              reason.value = "";
                              blockError.value = undefined;
                              blockReasonError.value = "";
                            }}
                          >
                            {m(props.i18n, "ui.action.block")}
                          </button>
                        ) : null}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pagination
              i18n={props.i18n}
              page={state.page}
              totalPages={state.users.totalPages}
              onPage$={(next) => {
                state.page = next;
                void load$();
              }}
            />
          </>
        ) : null}

        {state.blocking ? (
          <Dialog
            title={`${m(props.i18n, "ui.action.block")} - ${state.blocking.username}`}
            ctx={`user:${state.blocking.username}`}
            onClose$={() => (state.blocking = null)}
          >
            <form
              class="sv-form"
              preventdefault:submit
              onSubmit$={async (event: Event) => {
                const nextReason = formText(
                  event.target as HTMLFormElement,
                  "reason",
                );
                reason.value = nextReason;
                if (state.blocking)
                  await setUserStatus$(
                    state.blocking.username,
                    "blocked",
                    nextReason,
                  );
              }}
            >
              <Field
                label={m(props.i18n, "ui.field.reason")}
                error={
                  fieldErrorFor(blockError.value, "reason") ??
                  blockReasonError.value
                }
              >
                <textarea
                  name="reason"
                  class="sv-textarea"
                  value={reason.value}
                  onInput$={(event: Event) =>
                    (reason.value = (event.target as HTMLInputElement).value)
                  }
                />
              </Field>
              <div class="sv-form-actions">
                <button
                  type="button"
                  class="sv-button"
                  onClick$={() => (state.blocking = null)}
                >
                  {m(props.i18n, "ui.action.cancel")}
                </button>
                <button type="submit" class="sv-button sv-button--danger">
                  {m(props.i18n, "ui.action.block")}
                </button>
              </div>
            </form>
          </Dialog>
        ) : null}
      </>
    );
  },
);

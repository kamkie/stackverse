import { useState, type SubmitEvent } from "react";
import { ApiError, fieldErrorFor } from "../../api/problem";
import { useMe } from "../../auth/useMe";
import { Dialog } from "../../components/Dialog";
import { Field } from "../../components/Field";
import { Pagination } from "../../components/Pagination";
import { ErrorState, Loading } from "../../components/states";
import { useDebouncedValue } from "../../lib/useDebouncedValue";
import { useI18n } from "../../i18n/I18nContext";
import { useSetUserStatus, useUserAccounts, type UserAccount } from "./queries";

function BlockDialog({ user, onClose }: { user: UserAccount; onClose: () => void }) {
  const { t } = useI18n();
  const setStatus = useSetUserStatus();
  const [reason, setReason] = useState("");

  function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    setStatus.mutate(
      { username: user.username, status: "blocked", reason },
      { onSuccess: onClose },
    );
  }

  const error = setStatus.error;
  const conflict = error instanceof ApiError && error.status === 409;

  return (
    <Dialog
      title={`${t("ui.action.block")} — ${user.username}`}
      onClose={onClose}
      ctx={`user:${user.username}`}
    >
      <form className="sv-form" onSubmit={submit}>
        <Field
          label={t("ui.field.reason")}
          error={fieldErrorFor(error, "reason")}
        >
          <textarea
            className="sv-textarea"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            autoFocus
          />
        </Field>
        {conflict && (
          <div className="sv-alert sv-alert--warning" role="alert">
            {error.message}
          </div>
        )}
        <div className="sv-form-actions">
          <button type="button" className="sv-button" onClick={onClose}>
            {t("ui.action.cancel")}
          </button>
          <button
            type="submit"
            className="sv-button sv-button--danger"
            disabled={setStatus.isPending}
          >
            {t("ui.action.block")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

/** Searchable user directory with block/unblock (admin). */
export function UsersPage() {
  const { t, resolvedLanguage } = useI18n();
  const [search, setSearch] = useState("");
  const q = useDebouncedValue(search, 300);
  const [page, setPage] = useState(0);
  const [blocking, setBlocking] = useState<UserAccount | null>(null);

  const me = useMe();
  const users = useUserAccounts({ ...(q ? { q } : {}), page });
  const setStatus = useSetUserStatus();

  if (users.isError) return <ErrorState error={users.error} />;

  return (
    <>
      <h1 className="sv-page-title">{t("ui.admin.users")}</h1>
      <div className="sv-toolbar">
        <input
          type="search"
          className="sv-input"
          placeholder={t("ui.users.search.placeholder")}
          aria-label={t("ui.users.search.placeholder")}
          value={search}
          onChange={(e) => {
            setSearch(e.target.value);
            setPage(0);
          }}
        />
      </div>
      {users.isPending ? (
        <Loading />
      ) : (
        <>
          <div className="sv-table-wrap">
            <table className="sv-table">
              <thead>
                <tr>
                  <th scope="col">{t("ui.field.username")}</th>
                  <th scope="col">{t("ui.field.last-seen")}</th>
                  <th scope="col">{t("ui.field.bookmarks")}</th>
                  <th scope="col">{t("ui.field.status")}</th>
                  <th scope="col">
                    <span className="sv-visually-hidden">{t("ui.field.actions")}</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {users.data.items.map((user) => (
                  <tr key={user.username} data-ctx={`user:${user.username}`}>
                    <td>{user.username}</td>
                    <td>
                      <time dateTime={user.lastSeen}>
                        {new Date(user.lastSeen).toLocaleString(resolvedLanguage)}
                      </time>
                    </td>
                    <td>{user.bookmarkCount}</td>
                    <td>
                      {user.status === "blocked" ? (
                        <span
                          className="sv-badge sv-badge--danger"
                          title={user.blockedReason}
                        >
                          {t("ui.user.status.blocked")}
                        </span>
                      ) : (
                        <span className="sv-badge sv-badge--success">
                          {t("ui.user.status.active")}
                        </span>
                      )}
                    </td>
                    <td className="sv-cell-actions">
                      {user.status === "blocked" ? (
                        <button
                          type="button"
                          className="sv-button sv-button--sm"
                          disabled={setStatus.isPending}
                          onClick={() =>
                            setStatus.mutate({
                              username: user.username,
                              status: "active",
                            })
                          }
                        >
                          {t("ui.action.unblock")}
                        </button>
                      ) : (
                        // The API rejects self-blocking, so don't offer it.
                        // Wait for /me before showing any Block button — while
                        // it is pending every row would pass the !== check,
                        // including the admin's own.
                        me.data !== undefined &&
                        me.data.username !== user.username && (
                          <button
                            type="button"
                            className="sv-button sv-button--sm"
                            onClick={() => setBlocking(user)}
                          >
                            {t("ui.action.block")}
                          </button>
                        )
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pagination
            page={page}
            totalPages={users.data.totalPages}
            onPage={setPage}
          />
        </>
      )}
      {blocking && <BlockDialog user={blocking} onClose={() => setBlocking(null)} />}
    </>
  );
}

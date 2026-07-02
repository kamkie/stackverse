import { useState, type SubmitEvent } from "react";
import { fieldErrorFor } from "../../api/problem";
import { Dialog } from "../../components/Dialog";
import { Field } from "../../components/Field";
import { Pagination } from "../../components/Pagination";
import { ErrorState, Loading } from "../../components/states";
import { useDebouncedValue } from "../../lib/useDebouncedValue";
import { useI18n } from "../../i18n/I18nProvider";
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

  return (
    <Dialog title={`${t("ui.action.block")} — ${user.username}`} onClose={onClose}>
      <form className="sv-form" onSubmit={submit}>
        <Field
          label={t("ui.field.reason")}
          error={fieldErrorFor(setStatus.error, "reason")}
        >
          <textarea
            className="sv-textarea"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            autoFocus
          />
        </Field>
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
          placeholder={t("ui.bookmarks.search.placeholder")}
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
                  <th>{t("ui.field.username")}</th>
                  <th>{t("ui.field.last-seen")}</th>
                  <th>{t("ui.field.bookmarks")}</th>
                  <th>{t("ui.field.status")}</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {users.data.items.map((user) => (
                  <tr key={user.username}>
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
                          {user.status}
                        </span>
                      ) : (
                        <span className="sv-badge sv-badge--success">{user.status}</span>
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
                        <button
                          type="button"
                          className="sv-button sv-button--sm"
                          onClick={() => setBlocking(user)}
                        >
                          {t("ui.action.block")}
                        </button>
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

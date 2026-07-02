import { useMemo, useState } from "react";
import { Pagination } from "../../components/Pagination";
import { ErrorState, Loading } from "../../components/states";
import { useDebouncedValue } from "../../lib/useDebouncedValue";
import { useI18n } from "../../i18n/I18nProvider";
import { useAuditLog } from "./queries";

/** Filterable, paginated browser over the append-only audit trail (admin). */
export function AuditLogPage() {
  const { t, resolvedLanguage } = useI18n();
  const [actorInput, setActorInput] = useState("");
  const [actionInput, setActionInput] = useState("");
  const actor = useDebouncedValue(actorInput, 300);
  const action = useDebouncedValue(actionInput, 300);
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [page, setPage] = useState(0);

  const query = useMemo(
    () => ({
      ...(actor ? { actor } : {}),
      ...(action ? { action } : {}),
      ...(from ? { from: new Date(from).toISOString() } : {}),
      ...(to ? { to: new Date(to).toISOString() } : {}),
      page,
    }),
    [actor, action, from, to, page],
  );
  const audit = useAuditLog(query);

  if (audit.isError) return <ErrorState error={audit.error} />;

  return (
    <>
      <h1 className="sv-page-title">{t("ui.admin.audit")}</h1>
      <div className="sv-toolbar">
        <input
          className="sv-input"
          placeholder={t("ui.field.actor")}
          value={actorInput}
          onChange={(e) => {
            setActorInput(e.target.value);
            setPage(0);
          }}
        />
        <input
          className="sv-input"
          placeholder={t("ui.field.action")}
          value={actionInput}
          onChange={(e) => {
            setActionInput(e.target.value);
            setPage(0);
          }}
        />
        <input
          type="date"
          className="sv-input"
          value={from}
          onChange={(e) => {
            setFrom(e.target.value);
            setPage(0);
          }}
        />
        <input
          type="date"
          className="sv-input"
          value={to}
          onChange={(e) => {
            setTo(e.target.value);
            setPage(0);
          }}
        />
      </div>
      {audit.isPending ? (
        <Loading />
      ) : (
        <>
          <div className="sv-table-wrap">
            <table className="sv-table">
              <thead>
                <tr>
                  <th>{t("ui.field.created-at")}</th>
                  <th>{t("ui.field.actor")}</th>
                  <th>{t("ui.field.action")}</th>
                  <th>{t("ui.field.target")}</th>
                </tr>
              </thead>
              <tbody>
                {audit.data.items.map((entry) => (
                  <tr key={entry.id}>
                    <td>
                      <time dateTime={entry.createdAt}>
                        {new Date(entry.createdAt).toLocaleString(resolvedLanguage)}
                      </time>
                    </td>
                    <td>{entry.actor}</td>
                    <td>
                      <span className="sv-badge">{entry.action}</span>
                    </td>
                    <td className="sv-cell-mono">
                      {entry.targetType}/{entry.targetId.slice(0, 8)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pagination
            page={page}
            totalPages={audit.data.totalPages}
            onPage={setPage}
          />
        </>
      )}
    </>
  );
}

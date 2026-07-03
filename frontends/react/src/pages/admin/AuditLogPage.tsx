import { useMemo, useState } from "react";
import { Pagination } from "../../components/Pagination";
import { ErrorState, Loading } from "../../components/states";
import { useDebouncedValue } from "../../lib/useDebouncedValue";
import { useI18n } from "../../i18n/I18nProvider";
import { useAuditLog } from "./queries";

/**
 * Actions emitted by the reference backend, offered as <datalist> suggestions.
 * The contract keeps `action` an open string, so the field stays free-text.
 */
const KNOWN_ACTIONS = [
  "message.created",
  "message.updated",
  "message.deleted",
  "report.resolved",
  "bookmark.status-changed",
  "user.blocked",
  "user.unblocked",
];

const ACTION_DATALIST_ID = "audit-log-known-actions";

/**
 * The last instant of a local calendar day as an ISO instant. Backends store
 * microsecond timestamps and compare inclusively, so the millisecond Date
 * resolves to is extended to microseconds — otherwise entries in the day's
 * final millisecond would be filtered out.
 */
function endOfDayIso(day: string): string {
  return new Date(`${day}T23:59:59.999`).toISOString().replace(".999Z", ".999999Z");
}

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
      // The date inputs select whole local calendar days; the API takes instants
      // and the backend compares both bounds inclusively, so "from" becomes the
      // first instant of the selected day and "to" the last.
      ...(from ? { from: new Date(`${from}T00:00:00`).toISOString() } : {}),
      ...(to ? { to: endOfDayIso(to) } : {}),
      page,
    }),
    [actor, action, from, to, page],
  );
  const audit = useAuditLog(query);

  const clearFilters = () => {
    setActorInput("");
    setActionInput("");
    setFrom("");
    setTo("");
    setPage(0);
  };

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
          placeholder={t("ui.audit.action.placeholder")}
          aria-label={t("ui.audit.action.placeholder")}
          list={ACTION_DATALIST_ID}
          value={actionInput}
          onChange={(e) => {
            setActionInput(e.target.value);
            setPage(0);
          }}
        />
        <datalist id={ACTION_DATALIST_ID}>
          {KNOWN_ACTIONS.map((knownAction) => (
            <option key={knownAction} value={knownAction} />
          ))}
        </datalist>
        <label className="sv-toolbar-field">
          <span className="sv-label">{t("ui.field.from")}</span>
          <input
            type="date"
            className="sv-input"
            value={from}
            onChange={(e) => {
              setFrom(e.target.value);
              setPage(0);
            }}
          />
        </label>
        <label className="sv-toolbar-field">
          <span className="sv-label">{t("ui.field.to")}</span>
          <input
            type="date"
            className="sv-input"
            value={to}
            onChange={(e) => {
              setTo(e.target.value);
              setPage(0);
            }}
          />
        </label>
        <button
          type="button"
          className="sv-button sv-button--ghost"
          onClick={clearFilters}
        >
          {t("ui.action.clear-filters")}
        </button>
      </div>
      {audit.isPending ? (
        <Loading />
      ) : (
        <>
          <div className="sv-table-wrap">
            <table className="sv-table">
              <thead>
                <tr>
                  <th scope="col">{t("ui.field.created-at")}</th>
                  <th scope="col">{t("ui.field.actor")}</th>
                  <th scope="col">{t("ui.field.action")}</th>
                  <th scope="col">{t("ui.field.target")}</th>
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

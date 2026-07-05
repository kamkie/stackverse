import { useMemo, useState, type SubmitEvent } from "react";
import { ApiError, fieldErrorFor } from "../../api/problem";
import { ConfirmDialog } from "../../components/ConfirmDialog";
import { Dialog } from "../../components/Dialog";
import { Field } from "../../components/Field";
import { Pagination } from "../../components/Pagination";
import { ErrorState, Loading } from "../../components/states";
import { useToast } from "../../components/ToastContext";
import { useDebouncedValue } from "../../lib/useDebouncedValue";
import { SUPPORTED_LANGUAGES } from "../../i18n/languages";
import { useI18n } from "../../i18n/I18nContext";
import {
  useCreateMessage,
  useDeleteMessage,
  useMessages,
  useUpdateMessage,
  type Message,
  type MessageInput,
} from "./queries";

function MessageFormDialog({
  message,
  onClose,
}: {
  message?: Message | undefined;
  onClose: () => void;
}) {
  const { t } = useI18n();
  const toast = useToast();
  const create = useCreateMessage();
  const update = useUpdateMessage();
  const mutation = message ? update : create;

  const [key, setKey] = useState(message?.key ?? "");
  const [language, setLanguage] = useState(message?.language ?? "en");
  const [text, setText] = useState(message?.text ?? "");
  const [description, setDescription] = useState(message?.description ?? "");

  function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    const body: MessageInput = {
      key,
      language,
      text,
      ...(description ? { description } : {}),
    };
    const options = {
      onSuccess: () => {
        toast.push(
          t(message ? "ui.toast.message-updated" : "ui.toast.message-created"),
        );
        onClose();
      },
    };
    if (message) update.mutate({ id: message.id, body }, options);
    else create.mutate(body, options);
  }

  const error = mutation.error;
  const conflict = error instanceof ApiError && error.status === 409;

  return (
    <Dialog
      title={t(message ? "ui.messages.dialog.edit" : "ui.messages.dialog.add")}
      onClose={onClose}
      ctx={message ? `message:${message.id}` : undefined}
    >
      <form className="sv-form" onSubmit={submit}>
        <Field label={t("ui.field.key")} error={fieldErrorFor(error, "key")}>
          <input
            className="sv-input"
            value={key}
            onChange={(e) => setKey(e.target.value)}
            autoFocus={!message}
          />
        </Field>
        <Field label={t("ui.field.language")} error={fieldErrorFor(error, "language")}>
          <select
            className="sv-select"
            value={language}
            onChange={(e) => setLanguage(e.target.value)}
          >
            {/* The contract allows any ISO 639-1 code; keep a message's own
                language selectable when it is outside the supported set. */}
            {(SUPPORTED_LANGUAGES as readonly string[]).includes(language)
              ? null
              : <option value={language}>{language}</option>}
            {SUPPORTED_LANGUAGES.map((code) => (
              <option key={code} value={code}>
                {code}
              </option>
            ))}
          </select>
        </Field>
        <Field label={t("ui.field.text")} error={fieldErrorFor(error, "text")}>
          <textarea
            className="sv-textarea"
            value={text}
            onChange={(e) => setText(e.target.value)}
          />
        </Field>
        <Field
          label={t("ui.field.description")}
          error={fieldErrorFor(error, "description")}
        >
          <textarea
            className="sv-textarea"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
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
            className="sv-button sv-button--primary"
            disabled={mutation.isPending}
          >
            {t("ui.action.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

/** Runtime-managed localized messages: list, create, edit, delete (admin). */
export function MessagesPage() {
  const { t } = useI18n();
  const toast = useToast();
  const [qInput, setQInput] = useState("");
  const q = useDebouncedValue(qInput, 300);
  const [language, setLanguage] = useState("");
  const [page, setPage] = useState(0);
  const [dialog, setDialog] = useState<
    { mode: "create" } | { mode: "edit"; message: Message } | null
  >(null);
  const [deleting, setDeleting] = useState<Message | null>(null);

  const query = useMemo(
    () => ({
      ...(q ? { q } : {}),
      ...(language ? { language } : {}),
      page,
    }),
    [q, language, page],
  );
  const messages = useMessages(query);
  const deleteMessage = useDeleteMessage();

  const clearFilters = () => {
    setQInput("");
    setLanguage("");
    setPage(0);
  };

  if (messages.isError) return <ErrorState error={messages.error} />;

  return (
    <>
      <h1 className="sv-page-title">{t("ui.admin.messages")}</h1>
      <div className="sv-toolbar">
        <input
          className="sv-input"
          placeholder={t("ui.messages.search.placeholder")}
          aria-label={t("ui.messages.search.placeholder")}
          value={qInput}
          onChange={(e) => {
            setQInput(e.target.value);
            setPage(0);
          }}
        />
        <select
          className="sv-select"
          aria-label={t("ui.field.language")}
          value={language}
          onChange={(e) => {
            setLanguage(e.target.value);
            setPage(0);
          }}
        >
          <option value="">{t("ui.messages.filter.all-languages")}</option>
          {SUPPORTED_LANGUAGES.map((code) => (
            <option key={code} value={code}>
              {code}
            </option>
          ))}
        </select>
        <button
          type="button"
          className="sv-button sv-button--ghost"
          onClick={clearFilters}
        >
          {t("ui.action.clear-filters")}
        </button>
        <button
          type="button"
          className="sv-button sv-button--primary"
          onClick={() => setDialog({ mode: "create" })}
        >
          {t("ui.action.add")}
        </button>
      </div>
      {messages.isPending ? (
        <Loading />
      ) : messages.data.items.length === 0 ? (
        <div className="sv-empty">{t("ui.messages.empty")}</div>
      ) : (
        <>
          <div className="sv-table-wrap">
            <table className="sv-table">
              <thead>
                <tr>
                  <th scope="col">{t("ui.field.key")}</th>
                  <th scope="col">{t("ui.field.language")}</th>
                  <th scope="col">{t("ui.field.text")}</th>
                  <th scope="col">
                    <span className="sv-visually-hidden">
                      {t("ui.field.actions")}
                    </span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {messages.data.items.map((message) => (
                  <tr key={message.id} data-ctx={`message:${message.id}`}>
                    <td className="sv-cell-mono">{message.key}</td>
                    <td>
                      <span className="sv-badge">{message.language}</span>
                    </td>
                    <td>{message.text}</td>
                    <td className="sv-cell-actions">
                      <button
                        type="button"
                        className="sv-button sv-button--ghost sv-button--sm"
                        onClick={() => setDialog({ mode: "edit", message })}
                      >
                        {t("ui.action.edit")}
                      </button>{" "}
                      <button
                        type="button"
                        className="sv-button sv-button--ghost sv-button--sm"
                        onClick={() => setDeleting(message)}
                      >
                        {t("ui.action.delete")}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pagination
            page={page}
            totalPages={messages.data.totalPages}
            onPage={setPage}
          />
        </>
      )}
      {dialog && (
        <MessageFormDialog
          message={dialog.mode === "edit" ? dialog.message : undefined}
          onClose={() => setDialog(null)}
        />
      )}
      {deleting && (
        <ConfirmDialog
          title={`${t("ui.action.delete")} — ${deleting.key}`}
          body={t("ui.confirm.delete-message")}
          ctx={`message:${deleting.id}`}
          confirmLabel={t("ui.action.delete")}
          cancelLabel={t("ui.action.cancel")}
          pending={deleteMessage.isPending}
          onConfirm={() =>
            deleteMessage.mutate(deleting.id, {
              onSuccess: () => {
                setDeleting(null);
                toast.push(t("ui.toast.message-deleted"));
              },
              onError: (error) =>
                toast.push(
                  error instanceof Error ? error.message : String(error),
                  "danger",
                ),
            })
          }
          onClose={() => setDeleting(null)}
        />
      )}
    </>
  );
}

import { useMemo, useState, type SubmitEvent } from "react";
import { ApiError, fieldErrorFor } from "../../api/problem";
import { Dialog } from "../../components/Dialog";
import { Field } from "../../components/Field";
import { Pagination } from "../../components/Pagination";
import { ErrorState, Loading } from "../../components/states";
import { useDebouncedValue } from "../../lib/useDebouncedValue";
import { useI18n } from "../../i18n/I18nProvider";
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
    const options = { onSuccess: onClose };
    if (message) update.mutate({ id: message.id, body }, options);
    else create.mutate(body, options);
  }

  const error = mutation.error;
  const conflict = error instanceof ApiError && error.status === 409;

  return (
    <Dialog
      title={message ? t("ui.action.edit") : t("ui.action.add")}
      onClose={onClose}
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
          <input
            className="sv-input"
            value={language}
            onChange={(e) => setLanguage(e.target.value)}
          />
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
  const [keyInput, setKeyInput] = useState("");
  const key = useDebouncedValue(keyInput, 300);
  const [language, setLanguage] = useState("");
  const [page, setPage] = useState(0);
  const [dialog, setDialog] = useState<
    { mode: "create" } | { mode: "edit"; message: Message } | null
  >(null);

  const query = useMemo(
    () => ({
      ...(key ? { key } : {}),
      ...(language ? { language } : {}),
      page,
    }),
    [key, language, page],
  );
  const messages = useMessages(query);
  const deleteMessage = useDeleteMessage();

  if (messages.isError) return <ErrorState error={messages.error} />;

  return (
    <>
      <h1 className="sv-page-title">{t("ui.admin.messages")}</h1>
      <div className="sv-toolbar">
        <input
          className="sv-input"
          placeholder={t("ui.field.key")}
          value={keyInput}
          onChange={(e) => {
            setKeyInput(e.target.value);
            setPage(0);
          }}
        />
        <select
          className="sv-select"
          value={language}
          onChange={(e) => {
            setLanguage(e.target.value);
            setPage(0);
          }}
        >
          <option value="">*</option>
          <option value="en">en</option>
          <option value="pl">pl</option>
        </select>
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
      ) : (
        <>
          <div className="sv-table-wrap">
            <table className="sv-table">
              <thead>
                <tr>
                  <th>{t("ui.field.key")}</th>
                  <th>{t("ui.field.language")}</th>
                  <th>{t("ui.field.text")}</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {messages.data.items.map((message) => (
                  <tr key={message.id}>
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
                        disabled={deleteMessage.isPending}
                        onClick={() => deleteMessage.mutate(message.id)}
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
    </>
  );
}

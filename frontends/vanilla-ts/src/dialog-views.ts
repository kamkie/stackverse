import { ApiError, fieldPathMatches, messageOf } from "./api";
import { state, SUPPORTED_LANGUAGES } from "./app-state";
import type { DialogState } from "./app-state";
import {
  t,
  escapeHtml,
  fieldError,
  textFieldHtml,
  textareaFieldHtml,
  selectFieldHtml,
} from "./view-helpers";
import type { ReportReason } from "./types";

export function dialogHtml(): string {
  if (!state.dialog) return "";
  switch (state.dialog.kind) {
    case "bookmark-form":
      return bookmarkFormDialogHtml(state.dialog);
    case "delete-bookmark":
      return confirmDialogHtml({
        title: t("ui.action.delete"),
        body: t("ui.confirm.delete-bookmark"),
        confirm: t("ui.action.delete"),
        action: "confirm-bookmark-delete",
        ctx: `bookmark:${state.dialog.bookmark.id}`,
      });
    case "report-bookmark":
      return reportBookmarkDialogHtml(state.dialog);
    case "edit-report":
      return editReportDialogHtml(state.dialog);
    case "withdraw-report":
      return confirmDialogHtml({
        title: t("ui.action.withdraw"),
        body: t("ui.confirm.withdraw-report"),
        confirm: t("ui.action.withdraw"),
        action: "confirm-report-withdraw",
        ctx: `report:${state.dialog.report.id}`,
      });
    case "block-user":
      return blockUserDialogHtml(state.dialog);
    case "message-form":
      return messageFormDialogHtml(state.dialog);
    case "delete-message":
      return confirmDialogHtml({
        title: t("ui.action.delete"),
        body: t("ui.confirm.delete-message"),
        confirm: t("ui.action.delete"),
        action: "confirm-message-delete",
        ctx: `message:${state.dialog.message.id}`,
      });
  }
}

function dialogShell(title: string, body: string, ctx?: string): string {
  return `<dialog class="sv-dialog"${ctx ? ` data-ctx="${escapeHtml(ctx)}"` : ""}>
    <h2 class="sv-dialog-title">${escapeHtml(title)}</h2>
    ${body}
  </dialog>`;
}

function confirmDialogHtml({
  title,
  body,
  confirm,
  action,
  ctx,
}: {
  title: string;
  body: string;
  confirm: string;
  action: string;
  ctx: string;
}): string {
  return dialogShell(
    title,
    `<p>${escapeHtml(body)}</p>
    <div class="sv-form-actions">
      <button type="button" class="sv-button" data-action="close-dialog">${escapeHtml(t("ui.action.cancel"))}</button>
      <button type="button" class="sv-button sv-button--danger" data-action="${action}">${escapeHtml(confirm)}</button>
    </div>`,
    ctx,
  );
}

function nonFieldErrorHtml(
  error: unknown,
  fields: readonly string[],
  handledStatuses: readonly number[] = [],
): string {
  if (error === undefined || error === null) return "";
  if (
    error instanceof ApiError &&
    ((error.fieldErrors.length > 0 &&
      error.fieldErrors.every((entry) =>
        fields.some((field) => fieldPathMatches(entry.field, field)),
      )) ||
      handledStatuses.includes(error.status))
  ) {
    return "";
  }
  return `<div class="sv-alert sv-alert--danger" role="alert">${escapeHtml(messageOf(error))}</div>`;
}

function bookmarkFormDialogHtml(
  dialog: Extract<DialogState, { kind: "bookmark-form" }>,
): string {
  const bookmark = dialog.bookmark;
  const values = dialog.values ?? {
    url: bookmark?.url ?? "",
    title: bookmark?.title ?? "",
    notes: bookmark?.notes ?? "",
    tags: bookmark?.tags.join(" ") ?? "",
    visibility: bookmark?.visibility ?? "private",
  };
  const error = dialog.error;
  return dialogShell(
    t(
      dialog.mode === "edit"
        ? "ui.bookmarks.dialog.edit"
        : "ui.bookmarks.dialog.add",
    ),
    `<form class="sv-form" data-form="bookmark">
      ${textFieldHtml({ name: "url", label: t("ui.field.url"), value: values.url ?? "", error: fieldError(error, "url"), type: "url" })}
      ${textFieldHtml({ name: "title", label: t("ui.field.title"), value: values.title ?? "", error: fieldError(error, "title") })}
      ${textareaFieldHtml({ name: "notes", label: t("ui.field.notes"), value: values.notes ?? "", error: fieldError(error, "notes") })}
      ${textFieldHtml({ name: "tags", label: t("ui.field.tags"), value: values.tags ?? "", error: fieldError(error, "tags"), hint: t("ui.field.tags.hint") })}
      ${selectFieldHtml({
        name: "visibility",
        label: t("ui.field.visibility"),
        value: values.visibility ?? "private",
        error: fieldError(error, "visibility"),
        options: [
          { value: "private", label: t("ui.visibility.private") },
          { value: "public", label: t("ui.visibility.public") },
        ],
      })}
      ${error instanceof ApiError && error.status === 409 ? `<div class="sv-alert sv-alert--warning" role="alert">${escapeHtml(t("error.bookmark.hidden-publish"))}</div>` : ""}
      ${nonFieldErrorHtml(error, ["url", "title", "notes", "tags", "visibility"], [409])}
      <div class="sv-form-actions">
        <button type="button" class="sv-button" data-action="close-dialog">${escapeHtml(t("ui.action.cancel"))}</button>
        <button type="submit" class="sv-button sv-button--primary">${escapeHtml(t("ui.action.save"))}</button>
      </div>
    </form>`,
    bookmark ? `bookmark:${bookmark.id}` : undefined,
  );
}

const reportReasons: ReportReason[] = [
  "spam",
  "offensive",
  "broken-link",
  "other",
];

function reportBookmarkDialogHtml(
  dialog: Extract<DialogState, { kind: "report-bookmark" }>,
): string {
  const values = dialog.values ?? { reason: "spam", comment: "" };
  const error = dialog.error;
  return dialogShell(
    `${t("ui.action.report")} - ${dialog.bookmark.title}`,
    `<form class="sv-form" data-form="report-bookmark">
      ${selectFieldHtml({
        name: "reason",
        label: t("ui.field.reason"),
        value: values.reason ?? "spam",
        error: fieldError(error, "reason"),
        options: reportReasons.map((reason) => ({
          value: reason,
          label: t(`ui.report.reason.${reason}`),
        })),
      })}
      ${textareaFieldHtml({ name: "comment", label: t("ui.field.comment"), value: values.comment ?? "", error: fieldError(error, "comment") })}
      ${nonFieldErrorHtml(error, ["reason", "comment"])}
      <div class="sv-form-actions">
        <button type="button" class="sv-button" data-action="close-dialog">${escapeHtml(t("ui.action.cancel"))}</button>
        <button type="submit" class="sv-button sv-button--primary">${escapeHtml(t("ui.action.report"))}</button>
      </div>
    </form>`,
    `bookmark:${dialog.bookmark.id}`,
  );
}

function editReportDialogHtml(
  dialog: Extract<DialogState, { kind: "edit-report" }>,
): string {
  const values = dialog.values ?? {
    reason: dialog.report.reason,
    comment: dialog.report.comment ?? "",
  };
  const error = dialog.error;
  return dialogShell(
    t("ui.my-reports.dialog.edit"),
    `<form class="sv-form" data-form="edit-report">
      ${selectFieldHtml({
        name: "reason",
        label: t("ui.field.reason"),
        value: values.reason ?? "spam",
        error: fieldError(error, "reason"),
        options: reportReasons.map((reason) => ({
          value: reason,
          label: t(`ui.report.reason.${reason}`),
        })),
      })}
      ${textareaFieldHtml({ name: "comment", label: t("ui.field.comment"), value: values.comment ?? "", error: fieldError(error, "comment") })}
      ${error instanceof ApiError && error.status === 409 ? `<div class="sv-alert sv-alert--warning" role="alert">${escapeHtml(error.message)}</div>` : ""}
      ${nonFieldErrorHtml(error, ["reason", "comment"], [409])}
      <div class="sv-form-actions">
        <button type="button" class="sv-button" data-action="close-dialog">${escapeHtml(t("ui.action.cancel"))}</button>
        <button type="submit" class="sv-button sv-button--primary">${escapeHtml(t("ui.action.save"))}</button>
      </div>
    </form>`,
    `report:${dialog.report.id}`,
  );
}

function blockUserDialogHtml(
  dialog: Extract<DialogState, { kind: "block-user" }>,
): string {
  const values = dialog.values ?? { reason: "" };
  const error = dialog.error;
  return dialogShell(
    `${t("ui.action.block")} - ${dialog.user.username}`,
    `<form class="sv-form" data-form="block-user">
      ${textareaFieldHtml({ name: "reason", label: t("ui.field.reason"), value: values.reason ?? "", error: fieldError(error, "reason") })}
      ${error instanceof ApiError && error.status === 409 ? `<div class="sv-alert sv-alert--warning" role="alert">${escapeHtml(error.message)}</div>` : ""}
      ${nonFieldErrorHtml(error, ["reason"], [409])}
      <div class="sv-form-actions">
        <button type="button" class="sv-button" data-action="close-dialog">${escapeHtml(t("ui.action.cancel"))}</button>
        <button type="submit" class="sv-button sv-button--danger">${escapeHtml(t("ui.action.block"))}</button>
      </div>
    </form>`,
    `user:${dialog.user.username}`,
  );
}

function messageFormDialogHtml(
  dialog: Extract<DialogState, { kind: "message-form" }>,
): string {
  const message = dialog.message;
  const values = dialog.values ?? {
    key: message?.key ?? "",
    language: message?.language ?? "en",
    text: message?.text ?? "",
    description: message?.description ?? "",
  };
  const selectedLanguage = values.language ?? "en";
  const languageOptions = [
    ...(SUPPORTED_LANGUAGES.includes(
      selectedLanguage as (typeof SUPPORTED_LANGUAGES)[number],
    )
      ? []
      : [{ value: selectedLanguage, label: selectedLanguage }]),
    ...SUPPORTED_LANGUAGES.map((lang) => ({ value: lang, label: lang })),
  ];
  const error = dialog.error;
  return dialogShell(
    t(
      dialog.mode === "edit"
        ? "ui.messages.dialog.edit"
        : "ui.messages.dialog.add",
    ),
    `<form class="sv-form" data-form="message">
      ${textFieldHtml({ name: "key", label: t("ui.field.key"), value: values.key ?? "", error: fieldError(error, "key") })}
      ${selectFieldHtml({ name: "language", label: t("ui.field.language"), value: selectedLanguage, error: fieldError(error, "language"), options: languageOptions })}
      ${textareaFieldHtml({ name: "text", label: t("ui.field.text"), value: values.text ?? "", error: fieldError(error, "text") })}
      ${textareaFieldHtml({ name: "description", label: t("ui.field.description"), value: values.description ?? "", error: fieldError(error, "description") })}
      ${error instanceof ApiError && error.status === 409 ? `<div class="sv-alert sv-alert--warning" role="alert">${escapeHtml(error.message)}</div>` : ""}
      ${nonFieldErrorHtml(error, ["key", "language", "text", "description"], [409])}
      <div class="sv-form-actions">
        <button type="button" class="sv-button" data-action="close-dialog">${escapeHtml(t("ui.action.cancel"))}</button>
        <button type="submit" class="sv-button sv-button--primary">${escapeHtml(t("ui.action.save"))}</button>
      </div>
    </form>`,
    message ? `message:${message.id}` : undefined,
  );
}

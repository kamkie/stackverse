export const APP_ACTIONS = {
  theme: "theme",
  language: "language",
  logout: "logout",
  closeDialog: "close-dialog",
  toggleTag: "toggle-tag",
  loadMore: "load-more",
  page: "page",
  openBookmarkCreate: "open-bookmark-create",
  openBookmarkEdit: "open-bookmark-edit",
  openBookmarkDelete: "open-bookmark-delete",
  confirmBookmarkDelete: "confirm-bookmark-delete",
  openReport: "open-report",
  openReportEdit: "open-report-edit",
  openReportWithdraw: "open-report-withdraw",
  confirmReportWithdraw: "confirm-report-withdraw",
  resolveReport: "resolve-report",
  openBlockUser: "open-block-user",
  unblockUser: "unblock-user",
  clearAudit: "clear-audit",
  openMessageCreate: "open-message-create",
  openMessageEdit: "open-message-edit",
  openMessageDelete: "open-message-delete",
  confirmMessageDelete: "confirm-message-delete",
  clearMessages: "clear-messages",
} as const;

export type AppAction = (typeof APP_ACTIONS)[keyof typeof APP_ACTIONS];

const appActionValues = new Set<string>(Object.values(APP_ACTIONS));

export function parseAppAction(
  value: string | undefined,
): AppAction | undefined {
  return value && appActionValues.has(value) ? (value as AppAction) : undefined;
}

export function assertNever(value: never): never {
  throw new Error(`Unhandled app action: ${String(value)}`);
}

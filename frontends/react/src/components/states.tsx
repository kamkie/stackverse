import { ApiError, isUnauthorized } from "../api/problem";
import { LOGIN_URL } from "../auth/session";
import { useI18n } from "../i18n/I18nProvider";

export function Loading() {
  return (
    <div className="sv-loading" role="status">
      <span className="sv-spinner" />
    </div>
  );
}

/** A 401 means the session died — treat as logged out and offer login. */
export function LoginPrompt() {
  const { t } = useI18n();
  return (
    <div className="sv-empty">
      <a className="sv-button sv-button--primary" href={LOGIN_URL}>
        {t("ui.action.login")}
      </a>
    </div>
  );
}

export function ErrorState({ error }: { error: unknown }) {
  if (isUnauthorized(error)) return <LoginPrompt />;
  const message =
    error instanceof ApiError
      ? error.message
      : error instanceof Error
        ? error.message
        : String(error);
  return (
    <div className="sv-alert sv-alert--danger" role="alert">
      {message}
    </div>
  );
}

import { useId, type ReactElement } from "react";
import { cloneElement } from "react";
import type { FieldError } from "../api/problem";
import { useI18n } from "../i18n/I18nProvider";

interface FieldProps {
  label: string;
  error?: FieldError | undefined;
  hint?: string;
  /** A single input/textarea/select element; the field wires up id + aria. */
  children: ReactElement<{ id?: string; "aria-invalid"?: boolean; "aria-describedby"?: string }>;
}

/**
 * Form field with a label and an optional RFC 9457 field error. The error text
 * is localized client-side via its `messageKey` (the `validation.*` keys are in
 * the bundle), falling back to the server-localized `message`.
 */
export function Field({ label, error, hint, children }: FieldProps) {
  const { t } = useI18n();
  const id = useId();
  const errorId = `${id}-error`;
  const hintId = `${id}-hint`;

  const describedBy =
    [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(" ") ||
    undefined;
  const control = cloneElement(children, {
    id,
    ...(error ? { "aria-invalid": true } : {}),
    ...(describedBy ? { "aria-describedby": describedBy } : {}),
  });

  return (
    <div className={`sv-field${error ? " is-invalid" : ""}`}>
      <label className="sv-label" htmlFor={id}>
        {label}
      </label>
      {control}
      {hint && (
        <span className="sv-field-hint" id={hintId}>
          {hint}
        </span>
      )}
      {error && (
        <span className="sv-field-error" id={errorId}>
          {localizeFieldError(error, t)}
        </span>
      )}
    </div>
  );
}

export function localizeFieldError(
  error: FieldError,
  t: (key: string) => string,
): string {
  const localized = t(error.messageKey);
  // t() falls back to the key's last segment when the bundle lacks the key;
  // in that case the server-localized message is the better text.
  const keyFallback = error.messageKey.slice(error.messageKey.lastIndexOf(".") + 1);
  return localized === keyFallback ? error.message : localized;
}

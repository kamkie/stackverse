import type { FieldError } from "../api/problem";

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

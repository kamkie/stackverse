import { loadBundle } from "./i18n";
import { refreshSession } from "./session";

let initialization: Promise<void> | undefined;

export function initializeClient(): Promise<void> {
  initialization ??= Promise.all([loadBundle(), refreshSession()]).then(() => undefined);
  return initialization;
}

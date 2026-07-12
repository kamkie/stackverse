const STORAGE_KEY = "stackverse.reported";
const fallbackIds = new Set<string>();
let hydrated = false;

function readIds(): Set<string> {
  if (hydrated) return fallbackIds;
  hydrated = true;
  try {
    const stored = sessionStorage.getItem(STORAGE_KEY);
    for (const id of stored ? (JSON.parse(stored) as string[]) : [])
      fallbackIds.add(id);
  } catch {
    // The in-memory fallback keeps private/disabled storage modes functional.
  }
  return fallbackIds;
}

function writeIds(ids: Set<string>): void {
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify([...ids]));
  } catch {
    // The in-memory fallback keeps private/disabled storage modes functional.
  }
}

export function isReported(id: string): boolean {
  return readIds().has(id);
}

export function markReported(id: string): void {
  const ids = readIds();
  ids.add(id);
  writeIds(ids);
}

export function removeReported(id: string): void {
  const ids = readIds();
  ids.delete(id);
  writeIds(ids);
}

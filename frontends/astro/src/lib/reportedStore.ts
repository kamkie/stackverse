const STORAGE_KEY = "stackverse.reported";
const fallbackIds = new Set<string>();

function readIds(): Set<string> {
  try {
    const stored = sessionStorage.getItem(STORAGE_KEY);
    return new Set(stored ? (JSON.parse(stored) as string[]) : []);
  } catch {
    return new Set(fallbackIds);
  }
}

function writeIds(ids: Set<string>): void {
  fallbackIds.clear();
  ids.forEach((id) => fallbackIds.add(id));
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

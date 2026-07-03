/**
 * Browser-session memory of what this visitor already reported: the feed's
 * report button flips to a disabled "Reported" for these ids. The API's 409
 * duplicate handling stays the source of truth; withdrawing a report frees
 * the slot again (SPEC rule 13), so it must remove the id here too.
 */
const REPORTED_STORAGE_KEY = "stackverse.reported";

export function readReportedIds(): ReadonlySet<string> {
  try {
    const raw = sessionStorage.getItem(REPORTED_STORAGE_KEY);
    return new Set(raw ? (JSON.parse(raw) as string[]) : []);
  } catch {
    return new Set();
  }
}

function write(ids: ReadonlySet<string>): void {
  try {
    sessionStorage.setItem(REPORTED_STORAGE_KEY, JSON.stringify([...ids]));
  } catch {
    // storage unavailable — the state just won't survive navigation
  }
}

export function addReportedId(id: string): ReadonlySet<string> {
  const next = new Set(readReportedIds()).add(id);
  write(next);
  return next;
}

export function removeReportedId(id: string): ReadonlySet<string> {
  const next = new Set(readReportedIds());
  next.delete(id);
  write(next);
  return next;
}

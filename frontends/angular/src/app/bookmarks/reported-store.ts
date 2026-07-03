/**
 * Browser-session memory of what this visitor already reported: the feed's
 * report button flips to a disabled "Reported" for these ids. Ids arrive on
 * either proof of the state — a 201 create or a 409 duplicate (SPEC rule 13);
 * withdrawing a report frees the slot again, so it must remove the id here.
 */
const REPORTED_STORAGE_KEY = 'stackverse.reported';

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

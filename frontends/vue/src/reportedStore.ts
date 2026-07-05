import { ref } from "vue";

const REPORTED_STORAGE_KEY = "stackverse.reported";

function readReportedIds(): Set<string> {
  try {
    const raw = sessionStorage.getItem(REPORTED_STORAGE_KEY);
    return new Set(raw ? (JSON.parse(raw) as string[]) : []);
  } catch {
    return new Set();
  }
}

function writeReportedIds(ids: ReadonlySet<string>): void {
  try {
    sessionStorage.setItem(REPORTED_STORAGE_KEY, JSON.stringify([...ids]));
  } catch {
    // Storage can be disabled; the in-page marker still works for this visit.
  }
}

const reported = ref(readReportedIds());

export function isReported(bookmarkId: string): boolean {
  return reported.value.has(bookmarkId);
}

export function markReported(bookmarkId: string): void {
  const next = new Set(reported.value);
  next.add(bookmarkId);
  writeReportedIds(next);
  reported.value = next;
}

export function unmarkReported(bookmarkId: string): void {
  const next = new Set(reported.value);
  next.delete(bookmarkId);
  writeReportedIds(next);
  reported.value = next;
}

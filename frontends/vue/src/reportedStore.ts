import { ref } from "vue";

const reported = ref(new Set<string>());

export function isReported(bookmarkId: string): boolean {
  return reported.value.has(bookmarkId);
}

export function markReported(bookmarkId: string): void {
  const next = new Set(reported.value);
  next.add(bookmarkId);
  reported.value = next;
}

export function unmarkReported(bookmarkId: string): void {
  const next = new Set(reported.value);
  next.delete(bookmarkId);
  reported.value = next;
}

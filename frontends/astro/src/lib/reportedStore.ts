const reportedIds = new Set<string>();

export function isReported(id: string): boolean {
  return reportedIds.has(id);
}

export function markReported(id: string): void {
  reportedIds.add(id);
}

export function removeReported(id: string): void {
  reportedIds.delete(id);
}

import type { Page } from "./types";

export function previousPageForEmpty<T>(
  response: Page<T>,
  currentPage: number,
): number | null {
  if (response.items.length > 0 || currentPage <= 0) return null;
  const previousPage = Math.max(0, response.totalPages - 1);
  return previousPage < currentPage ? previousPage : null;
}

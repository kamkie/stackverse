interface PaginationProps {
  page: number;
  totalPages: number;
  onPage: (page: number) => void;
}

/** Prev/next control for the offset-paginated admin lists. */
export function Pagination({ page, totalPages, onPage }: PaginationProps) {
  if (totalPages <= 1) return null;
  return (
    <nav className="sv-pagination">
      <button
        type="button"
        className="sv-button sv-button--ghost sv-button--sm"
        disabled={page <= 0}
        onClick={() => onPage(page - 1)}
      >
        ‹
      </button>
      <span>
        {page + 1} / {totalPages}
      </span>
      <button
        type="button"
        className="sv-button sv-button--ghost sv-button--sm"
        disabled={page >= totalPages - 1}
        onClick={() => onPage(page + 1)}
      >
        ›
      </button>
    </nav>
  );
}

import { useI18n } from "../i18n/I18nContext";

interface PaginationProps {
  page: number;
  totalPages: number;
  onPage: (page: number) => void;
}

/** Prev/next control for the offset-paginated admin lists. */
export function Pagination({ page, totalPages, onPage }: PaginationProps) {
  const { t } = useI18n();
  if (totalPages <= 1) return null;
  return (
    <nav className="sv-pagination">
      <button
        type="button"
        className="sv-button sv-button--ghost sv-button--sm"
        disabled={page <= 0}
        aria-label={t("ui.action.previous")}
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
        aria-label={t("ui.action.next")}
        onClick={() => onPage(page + 1)}
      >
        ›
      </button>
    </nav>
  );
}

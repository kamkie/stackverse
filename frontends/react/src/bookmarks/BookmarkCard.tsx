import type { ReactNode } from "react";
import { useI18n } from "../i18n/I18nProvider";
import type { Bookmark } from "./queries";

interface BookmarkCardProps {
  bookmark: Bookmark;
  onToggleTag?: ((tag: string) => void) | undefined;
  activeTags?: string[];
  /** Owner or report actions, rendered on the meta line. */
  actions?: ReactNode;
}

export function BookmarkCard({
  bookmark,
  onToggleTag,
  activeTags = [],
  actions,
}: BookmarkCardProps) {
  const { t, resolvedLanguage } = useI18n();

  return (
    <li className="sv-card sv-bookmark">
      <div className="sv-bookmark-head">
        <h3 className="sv-bookmark-title">
          <a href={bookmark.url} target="_blank" rel="noopener noreferrer">
            {bookmark.title}
          </a>
        </h3>
        {bookmark.status === "hidden" && (
          <span className="sv-badge sv-badge--warning">
            {t("ui.bookmark.hidden")}
          </span>
        )}
        {bookmark.visibility === "public" && (
          <span className="sv-badge">{bookmark.visibility}</span>
        )}
      </div>
      <span className="sv-bookmark-url">{bookmark.url}</span>
      {bookmark.notes && <p className="sv-bookmark-notes">{bookmark.notes}</p>}
      <div className="sv-bookmark-meta">
        {(bookmark.tags?.length ?? 0) > 0 && (
          <ul className="sv-tag-list">
            {bookmark.tags?.map((tag) => (
              <li key={tag}>
                <button
                  type="button"
                  className={`sv-tag${activeTags.includes(tag) ? " is-active" : ""}`}
                  onClick={() => onToggleTag?.(tag)}
                  disabled={!onToggleTag}
                >
                  {tag}
                </button>
              </li>
            ))}
          </ul>
        )}
        <span>{bookmark.owner}</span>
        <time dateTime={bookmark.createdAt}>
          {new Date(bookmark.createdAt).toLocaleDateString(resolvedLanguage)}
        </time>
        {actions && <div className="sv-bookmark-actions">{actions}</div>}
      </div>
    </li>
  );
}

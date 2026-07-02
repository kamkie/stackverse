import { useI18n } from "../i18n/I18nProvider";
import { useTags } from "./queries";

interface TagSidebarProps {
  activeTags: string[];
  onToggleTag: (tag: string) => void;
}

/** The caller's tags with usage counts (`GET /api/v1/tags`); click to filter. */
export function TagSidebar({ activeTags, onToggleTag }: TagSidebarProps) {
  const { t } = useI18n();
  const tags = useTags();

  return (
    <aside className="sv-sidebar">
      <h2 className="sv-sidebar-title">{t("ui.nav.tags")}</h2>
      {tags.data && tags.data.tags.length > 0 ? (
        <ul className="sv-tag-list">
          {tags.data.tags.map(({ tag, count }) => (
            <li key={tag}>
              <button
                type="button"
                className={`sv-tag${activeTags.includes(tag) ? " is-active" : ""}`}
                onClick={() => onToggleTag(tag)}
              >
                {tag} <span className="sv-tag-count">{count}</span>
              </button>
            </li>
          ))}
        </ul>
      ) : (
        <span className="sv-field-hint">—</span>
      )}
    </aside>
  );
}

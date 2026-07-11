import { createSignal, onCleanup, onMount, Show } from "solid-js";
import { useIsland } from "../../lib/island";
import { session } from "../../lib/session";
import TagSidebar from "./TagSidebar";

export const BOOKMARK_TAG_SELECTED = "stackverse:bookmark-tag-selected";
export const BOOKMARK_TAGS_CHANGED = "stackverse:bookmark-tags-changed";

export default function BookmarkTagNavigation() {
  const island = useIsland();
  const [selected, setSelected] = createSignal("");
  const [reloadKey, setReloadKey] = createSignal(0);

  function select(tag: string) {
    setSelected(tag);
    window.dispatchEvent(
      new CustomEvent(BOOKMARK_TAG_SELECTED, { detail: tag }),
    );
  }

  onMount(() => {
    const reload = () => setReloadKey((current) => current + 1);
    window.addEventListener(BOOKMARK_TAGS_CHANGED, reload);
    onCleanup(() => window.removeEventListener(BOOKMARK_TAGS_CHANGED, reload));
  });

  return (
    <Show when={island.ready() && session()?.authenticated}>
      <TagSidebar
        selected={selected()}
        reloadKey={reloadKey()}
        onSelect={select}
      />
    </Show>
  );
}

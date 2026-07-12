import { createSignal, Show } from "solid-js";
import { BOOKMARK_TAG_SELECTED } from "../../lib/events";
import { useIsland } from "../../lib/island";
import { session } from "../../lib/session";
import TagSidebar from "./TagSidebar";

export default function BookmarkTagNavigation() {
  const island = useIsland();
  const [selected, setSelected] = createSignal("");

  function select(tag: string) {
    setSelected(tag);
    window.dispatchEvent(
      new CustomEvent(BOOKMARK_TAG_SELECTED, { detail: tag }),
    );
  }

  return (
    <Show when={island.ready() && session()?.authenticated}>
      <TagSidebar selected={selected()} onSelect={select} />
    </Show>
  );
}

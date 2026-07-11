import { createEffect, createSignal, For } from "solid-js";
import { api } from "../lib/api";
import { i18n, m } from "../lib/i18n";
import type { TagCount } from "../lib/types";

interface Props {
  selected?: string;
  reloadKey?: number;
  onSelect: (tag: string) => void;
}

export default function TagSidebar(props: Props) {
  const [tags, setTags] = createSignal<TagCount[]>([]);

  async function reload() {
    try {
      const response = await api<{ tags: TagCount[] }>("/api/v1/tags");
      setTags(response.tags);
    } catch {
      setTags([]);
    }
  }

  createEffect(() => {
    props.reloadKey;
    void reload();
  });

  return (
    <aside class="sv-sidebar">
      <h2 class="sv-sidebar-title">{m(i18n(), "ui.nav.tags")}</h2>
      <ul class="sv-tag-list">
        <For each={tags()}>
          {(item) => (
            <li>
              <button
                type="button"
                class={`sv-tag${props.selected === item.tag ? " is-active" : ""}`}
                onClick={() => props.onSelect(props.selected === item.tag ? "" : item.tag)}
              >
                {item.tag}
                <span class="sv-tag-count">{item.count}</span>
              </button>
            </li>
          )}
        </For>
      </ul>
    </aside>
  );
}

import {
  component$,
  useStore,
  useVisibleTask$,
  type PropFunction,
} from "@builder.io/qwik";
import { api } from "../lib/api";
import { m, type I18nState } from "../lib/i18n";
import type { TagCount } from "../lib/types";

interface Props {
  i18n: I18nState;
  selected?: string;
  reloadKey?: number;
  onSelect$: PropFunction<(tag: string) => void>;
}

export default component$<Props>((props) => {
  const state = useStore<{ tags: TagCount[] }>({ tags: [] });

  useVisibleTask$(async ({ track }) => {
    track(() => props.reloadKey);
    try {
      const response = await api<{ tags: TagCount[] }>("/api/v1/tags");
      state.tags = response.tags;
    } catch {
      state.tags = [];
    }
  });

  return (
    <aside class="sv-sidebar">
      <h2 class="sv-sidebar-title">{m(props.i18n, "ui.nav.tags")}</h2>
      <ul class="sv-tag-list">
        {state.tags.map((item) => (
          <li key={item.tag}>
            <button
              type="button"
              class={`sv-tag${props.selected === item.tag ? " is-active" : ""}`}
              onClick$={() =>
                props.onSelect$(props.selected === item.tag ? "" : item.tag)
              }
            >
              {item.tag}
              <span class="sv-tag-count">{item.count}</span>
            </button>
          </li>
        ))}
      </ul>
    </aside>
  );
});

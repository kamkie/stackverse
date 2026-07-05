<script lang="ts">
  import { onMount } from "svelte";
  import { api } from "../lib/api";
  import { i18n, m } from "../lib/i18n";
  import type { TagCount } from "../lib/types";
  import { fromStore } from "svelte/store";

  interface Props {
    selected?: string;
    onSelect: (tag: string) => void;
  }

  let { selected = "", onSelect }: Props = $props();
  let tags: TagCount[] = $state([]);
  const i18nState = fromStore(i18n);

  export async function reload() {
    try {
      const response = await api<{ tags: TagCount[] }>("/api/v1/tags");
      tags = response.tags;
    } catch {
      tags = [];
    }
  }

  onMount(() => {
    void reload();
  });
</script>

<aside class="sv-sidebar">
  <h2 class="sv-sidebar-title">{m(i18nState.current, "ui.nav.tags")}</h2>
  <ul class="sv-tag-list">
    {#each tags as item}
      <li>
        <button
          type="button"
          class={`sv-tag${selected === item.tag ? " is-active" : ""}`}
          onclick={() => onSelect(selected === item.tag ? "" : item.tag)}
        >
          {item.tag}
          <span class="sv-tag-count">{item.count}</span>
        </button>
      </li>
    {/each}
  </ul>
</aside>

<script setup lang="ts">
import { computed } from "vue";
import { session } from "../auth";
import { t } from "../i18n/i18n";
import { isReported } from "../reportedStore";
import type { Bookmark } from "../types";

const props = defineProps<{
  bookmark: Bookmark;
  mode: "mine" | "feed";
}>();

const emit = defineEmits<{
  edit: [bookmark: Bookmark];
  delete: [bookmark: Bookmark];
  report: [bookmark: Bookmark];
}>();

const reported = computed(() => isReported(props.bookmark.id));
</script>

<template>
  <article class="sv-card sv-bookmark" :data-ctx="`bookmark:${bookmark.id}`">
    <div class="sv-bookmark-head">
      <h2 class="sv-bookmark-title">{{ bookmark.title }}</h2>
      <span v-if="bookmark.status === 'hidden'" class="sv-badge sv-badge--warning">
        {{ t("ui.bookmark.hidden") }}
      </span>
      <div class="sv-bookmark-actions">
        <template v-if="mode === 'mine'">
          <button
            type="button"
            class="sv-button sv-button--ghost sv-button--sm"
            @click="emit('edit', bookmark)"
          >
            {{ t("ui.action.edit") }}
          </button>
          <button
            type="button"
            class="sv-button sv-button--danger sv-button--sm"
            @click="emit('delete', bookmark)"
          >
            {{ t("ui.action.delete") }}
          </button>
        </template>
        <template v-else-if="session?.authenticated">
          <button
            v-if="reported"
            type="button"
            class="sv-button sv-button--sm"
            disabled
          >
            {{ t("ui.report.reported") }}
          </button>
          <button
            v-else
            type="button"
            class="sv-button sv-button--ghost sv-button--sm"
            @click="emit('report', bookmark)"
          >
            {{ t("ui.action.report") }}
          </button>
        </template>
      </div>
    </div>
    <a class="sv-bookmark-url" :href="bookmark.url" target="_blank" rel="noreferrer">
      {{ bookmark.url }}
    </a>
    <p v-if="bookmark.notes" class="sv-bookmark-notes">{{ bookmark.notes }}</p>
    <div class="sv-bookmark-meta">
      <span>{{ bookmark.owner }}</span>
      <span>{{ bookmark.visibility }}</span>
      <ul v-if="(bookmark.tags ?? []).length" class="sv-tag-list">
        <li v-for="tag in bookmark.tags ?? []" :key="tag" class="sv-tag">{{ tag }}</li>
      </ul>
    </div>
  </article>
</template>

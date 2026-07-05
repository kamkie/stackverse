<script setup lang="ts">
import { t } from "../i18n/i18n";

defineProps<{
  page: number;
  totalPages: number;
}>();

const emit = defineEmits<{
  page: [value: number];
}>();
</script>

<template>
  <nav v-if="totalPages > 1" class="sv-pagination">
    <button
      type="button"
      class="sv-button sv-button--ghost sv-button--sm"
      :disabled="page <= 0"
      :aria-label="t('ui.action.previous')"
      @click="emit('page', page - 1)"
    >
      ‹
    </button>
    <span>{{ page + 1 }} / {{ totalPages }}</span>
    <button
      type="button"
      class="sv-button sv-button--ghost sv-button--sm"
      :disabled="page + 1 >= totalPages"
      :aria-label="t('ui.action.next')"
      @click="emit('page', page + 1)"
    >
      ›
    </button>
  </nav>
</template>

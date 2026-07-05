<script setup lang="ts">
defineProps<{
  page: number;
  totalPages: number;
}>();

const emit = defineEmits<{
  page: [value: number];
}>();
</script>

<template>
  <div v-if="totalPages > 1" class="sv-pagination">
    <button
      type="button"
      class="sv-button sv-button--ghost sv-button--sm"
      :disabled="page <= 0"
      @click="emit('page', page - 1)"
    >
      Previous
    </button>
    <span>Page {{ page + 1 }} / {{ totalPages }}</span>
    <button
      type="button"
      class="sv-button sv-button--ghost sv-button--sm"
      :disabled="page + 1 >= totalPages"
      @click="emit('page', page + 1)"
    >
      Next
    </button>
  </div>
</template>

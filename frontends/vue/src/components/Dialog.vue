<script setup lang="ts">
defineProps<{
  title: string;
  ctx?: string | undefined;
}>();

const emit = defineEmits<{
  close: [];
}>();
</script>

<template>
  <dialog class="sv-dialog" open :data-ctx="ctx">
    <h2 class="sv-dialog-title">{{ title }}</h2>
    <slot />
    <button
      type="button"
      class="sv-visually-hidden"
      aria-label="Close"
      @click="emit('close')"
    />
  </dialog>
</template>

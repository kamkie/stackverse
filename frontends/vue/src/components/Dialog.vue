<script setup lang="ts">
import { onMounted, ref } from "vue";

defineProps<{
  title: string;
  ctx?: string | undefined;
}>();

const emit = defineEmits<{
  close: [];
}>();

const dialog = ref<HTMLDialogElement | null>(null);

onMounted(() => {
  const element = dialog.value;
  if (!element || element.open) return;
  if (typeof element.showModal === "function") element.showModal();
  else element.setAttribute("open", "");
});
</script>

<template>
  <dialog ref="dialog" class="sv-dialog" :data-ctx="ctx" @close="emit('close')">
    <h2 class="sv-dialog-title">{{ title }}</h2>
    <slot />
  </dialog>
</template>

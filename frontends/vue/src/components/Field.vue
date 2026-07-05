<script setup lang="ts">
defineProps<{
  label: string;
  error?: string | undefined;
  hint?: string | undefined;
  forId?: string | undefined;
}>();
</script>

<template>
  <label class="sv-field" :class="{ 'is-invalid': error }">
    <span class="sv-label">{{ label }}</span>
    <slot />
    <span v-if="hint" class="sv-field-hint">{{ hint }}</span>
    <span v-if="error" class="sv-field-error">{{ error }}</span>
  </label>
</template>

<script setup lang="ts">
import { computed, useId } from "vue";

defineOptions({ name: "StackverseField" });

const props = defineProps<{
    label: string;
    error?: string | undefined;
    hint?: string | undefined;
}>();

defineSlots<{
    default(props: { inputId: string; describedBy: string | undefined; invalid: boolean }): unknown;
}>();

const inputId = useId();
const hintId = `${inputId}-hint`;
const errorId = `${inputId}-error`;
const describedBy = computed(() => {
    const ids = [props.hint ? hintId : "", props.error ? errorId : ""].filter(Boolean);
    return ids.length > 0 ? ids.join(" ") : undefined;
});
const invalid = computed(() => Boolean(props.error));
</script>

<template>
    <label class="sv-field" :class="{ 'is-invalid': error }" :for="inputId">
        <span class="sv-label">{{ label }}</span>
        <slot :input-id="inputId" :described-by="describedBy" :invalid="invalid" />
        <span v-if="hint" :id="hintId" class="sv-field-hint">{{ hint }}</span>
        <span v-if="error" :id="errorId" class="sv-field-error" role="alert">{{ error }}</span>
    </label>
</template>

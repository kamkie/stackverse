<script setup lang="ts">
import { toasts } from "../toast";
</script>

<template>
    <div class="sv-toast-region" role="status" aria-live="polite">
        <div
            v-for="toast in toasts"
            :key="toast.id"
            class="sv-toast"
            :class="`sv-toast--${toast.tone}`"
        >
            {{ toast.message }}
        </div>
    </div>
</template>

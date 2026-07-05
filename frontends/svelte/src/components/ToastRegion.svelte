<script lang="ts">
  export interface Toast {
    id: number;
    message: string;
    tone: "success" | "danger";
  }

  let { toasts = [] }: { toasts?: Toast[] } = $props();
</script>

<div class="sv-toast-region" role="status" aria-live="polite">
  {#each toasts as toast (toast.id)}
    <div class={`sv-toast ${toast.tone === "success" ? "sv-toast--success" : "sv-toast--danger"}`}>
      {toast.message}
    </div>
  {/each}
</div>

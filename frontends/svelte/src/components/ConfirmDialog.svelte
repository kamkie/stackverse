<script lang="ts">
  import Dialog from "./Dialog.svelte";

  export let title: string;
  export let body: string;
  export let confirmLabel: string;
  export let cancelLabel: string;
  export let pending = false;
  export let ctx: string | undefined = undefined;
  export let danger = true;
  export let onConfirm: () => void | Promise<void>;
  export let onClose: () => void;
</script>

<Dialog {title} {ctx} on:close={onClose}>
  <form
    class="sv-form"
    on:submit|preventDefault={() => {
      void onConfirm();
    }}
  >
    <p>{body}</p>
    <div class="sv-form-actions">
      <button type="button" class="sv-button" on:click={onClose}>{cancelLabel}</button>
      <button
        type="submit"
        class={`sv-button ${danger ? "sv-button--danger" : "sv-button--primary"}`}
        disabled={pending}
      >
        {confirmLabel}
      </button>
    </div>
  </form>
</Dialog>

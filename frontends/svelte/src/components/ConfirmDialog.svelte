<script lang="ts">
  import Dialog from "./Dialog.svelte";

  interface Props {
    title: string;
    body: string;
    confirmLabel: string;
    cancelLabel: string;
    pending?: boolean;
    ctx?: string;
    danger?: boolean;
    onConfirm: () => void | Promise<void>;
    onClose: () => void;
  }

  let {
    title,
    body,
    confirmLabel,
    cancelLabel,
    pending = false,
    ctx = undefined,
    danger = true,
    onConfirm,
    onClose,
  }: Props = $props();

  function submit(event: SubmitEvent) {
    event.preventDefault();
    void onConfirm();
  }
</script>

<Dialog {title} {ctx} {onClose}>
  <form class="sv-form" onsubmit={submit}>
    <p>{body}</p>
    <div class="sv-form-actions">
      <button type="button" class="sv-button" onclick={onClose}
        >{cancelLabel}</button
      >
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

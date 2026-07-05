<script lang="ts">
  import { onMount, type Snippet } from "svelte";

  interface Props {
    title: string;
    ctx?: string;
    onClose: () => void;
    children: Snippet;
  }

  let { title, ctx = undefined, onClose, children }: Props = $props();
  let dialog: HTMLDialogElement | undefined = $state();

  onMount(() => {
    dialog?.showModal();
    return () => {
      if (dialog?.open) dialog.close();
    };
  });
</script>

<dialog
  bind:this={dialog}
  class="sv-dialog"
  data-ctx={ctx}
  oncancel={(event) => {
    event.preventDefault();
    onClose();
  }}
  onclose={onClose}
>
  <h2 class="sv-dialog-title">{title}</h2>
  {@render children()}
</dialog>

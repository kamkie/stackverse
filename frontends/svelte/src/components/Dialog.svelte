<script lang="ts">
  import { createEventDispatcher, onMount } from "svelte";

  export let title: string;
  export let ctx: string | undefined = undefined;

  const dispatch = createEventDispatcher<{ close: void }>();
  let dialog: HTMLDialogElement;

  onMount(() => {
    dialog.showModal();
    return () => {
      if (dialog.open) dialog.close();
    };
  });

  function close() {
    dispatch("close");
  }
</script>

<dialog
  bind:this={dialog}
  class="sv-dialog"
  data-ctx={ctx}
  on:cancel|preventDefault={close}
  on:close={close}
>
  <h2 class="sv-dialog-title">{title}</h2>
  <slot />
</dialog>

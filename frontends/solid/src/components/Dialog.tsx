import { type JSX, onCleanup, onMount } from "solid-js";

interface Props {
  title: string;
  ctx?: string;
  onClose: () => void;
  children: JSX.Element;
}

export default function Dialog(props: Props) {
  let dialog!: HTMLDialogElement;

  onMount(() => {
    dialog.showModal();
  });

  onCleanup(() => {
    if (dialog.open) dialog.close();
  });

  return (
    <dialog
      ref={dialog}
      class="sv-dialog"
      data-ctx={props.ctx}
      onCancel={(event) => {
        event.preventDefault();
        props.onClose();
      }}
      onClose={() => props.onClose()}
    >
      <h2 class="sv-dialog-title">{props.title}</h2>
      {props.children}
    </dialog>
  );
}

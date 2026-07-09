import {
  Slot,
  component$,
  useSignal,
  useVisibleTask$,
  type PropFunction,
} from "@builder.io/qwik";

interface Props {
  title: string;
  ctx?: string;
  onClose$: PropFunction<() => void>;
}

export default component$<Props>((props) => {
  const dialog = useSignal<HTMLDialogElement>();

  useVisibleTask$(({ cleanup }) => {
    const element = dialog.value;
    if (element && !element.open) element.showModal();
    cleanup(() => {
      if (element?.open) element.close();
    });
  });

  return (
    <dialog
      ref={dialog}
      class="sv-dialog"
      data-ctx={props.ctx}
      onCancel$={(event: Event) => {
        event.preventDefault();
        void props.onClose$();
      }}
      onClose$={() => props.onClose$()}
    >
      <h2 class="sv-dialog-title">{props.title}</h2>
      <Slot />
    </dialog>
  );
});

import { component$, type PropFunction } from "@builder.io/qwik";
import Dialog from "./Dialog";

interface Props {
  title: string;
  body: string;
  confirmLabel: string;
  cancelLabel: string;
  pending?: boolean;
  ctx?: string;
  danger?: boolean;
  onConfirm$: PropFunction<() => void | Promise<void>>;
  onClose$: PropFunction<() => void>;
}

export default component$<Props>((props) => {
  return (
    <Dialog title={props.title} ctx={props.ctx} onClose$={props.onClose$}>
      <form
        class="sv-form"
        preventdefault:submit
        onSubmit$={(event: Event) => {
          void props.onConfirm$();
        }}
      >
        <p>{props.body}</p>
        <div class="sv-form-actions">
          <button type="button" class="sv-button" onClick$={props.onClose$}>
            {props.cancelLabel}
          </button>
          <button
            type="submit"
            class={`sv-button ${props.danger === false ? "sv-button--primary" : "sv-button--danger"}`}
            disabled={props.pending}
          >
            {props.confirmLabel}
          </button>
        </div>
      </form>
    </Dialog>
  );
});

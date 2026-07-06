import Dialog from "./Dialog";

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

export default function ConfirmDialog(props: Props) {
  function submit(event: SubmitEvent) {
    event.preventDefault();
    void props.onConfirm();
  }

  return (
    <Dialog title={props.title} ctx={props.ctx} onClose={props.onClose}>
      <form class="sv-form" onSubmit={submit}>
        <p>{props.body}</p>
        <div class="sv-form-actions">
          <button type="button" class="sv-button" onClick={props.onClose}>
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
}

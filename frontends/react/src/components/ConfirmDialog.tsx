import { Dialog } from "./Dialog";

interface ConfirmDialogProps {
  title: string;
  body: string;
  confirmLabel: string;
  cancelLabel: string;
  pending?: boolean;
  onConfirm: () => void;
  onClose: () => void;
  /** Entity being confirmed, as `<type>:<id>` — see Dialog's ctx prop. */
  ctx?: string | undefined;
}

/**
 * Confirmation modal for destructive actions. All strings arrive already
 * localized — the caller owns the message keys.
 */
export function ConfirmDialog({
  title,
  body,
  confirmLabel,
  cancelLabel,
  pending,
  onConfirm,
  onClose,
  ctx,
}: ConfirmDialogProps) {
  return (
    <Dialog title={title} onClose={onClose} ctx={ctx}>
      <p>{body}</p>
      <div className="sv-form-actions">
        <button type="button" className="sv-button" onClick={onClose}>
          {cancelLabel}
        </button>
        <button
          type="button"
          className="sv-button sv-button--danger"
          disabled={pending}
          onClick={onConfirm}
        >
          {confirmLabel}
        </button>
      </div>
    </Dialog>
  );
}

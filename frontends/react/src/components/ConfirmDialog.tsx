import { Dialog } from "./Dialog";

interface ConfirmDialogProps {
  title: string;
  body: string;
  confirmLabel: string;
  cancelLabel: string;
  pending?: boolean;
  onConfirm: () => void;
  onClose: () => void;
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
}: ConfirmDialogProps) {
  return (
    <Dialog title={title} onClose={onClose}>
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

import { useEffect, useRef, type ReactNode } from "react";

interface DialogProps {
  title: string;
  onClose: () => void;
  children: ReactNode;
}

/** Modal dialog on top of the native `<dialog>` element. Render it conditionally. */
export function Dialog({ title, onClose, children }: DialogProps) {
  const ref = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog || dialog.open) return;
    // jsdom has no showModal; falling back to the open attribute keeps the
    // dialog testable.
    if (typeof dialog.showModal === "function") dialog.showModal();
    else dialog.setAttribute("open", "");
  }, []);

  return (
    <dialog ref={ref} className="sv-dialog" onClose={onClose}>
      <h2 className="sv-dialog-title">{title}</h2>
      {children}
    </dialog>
  );
}

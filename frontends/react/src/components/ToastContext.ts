import { createContext, useContext } from "react";

export type ToastVariant = "success" | "danger";

export interface ToastContextValue {
  /** Shows a toast that auto-dismisses; `message` is already localized. */
  push: (message: string, variant?: ToastVariant) => void;
}

export const ToastContext = createContext<ToastContextValue | null>(null);

export function useToast(): ToastContextValue {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error("useToast must be used inside <ToastProvider>");
  return ctx;
}

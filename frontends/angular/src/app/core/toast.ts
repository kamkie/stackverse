import { Injectable, signal } from '@angular/core';

export type ToastVariant = 'success' | 'danger';

export interface ToastItem {
  id: number;
  message: string;
  variant: ToastVariant;
}

const TOAST_DURATION_MS = 5000;

/**
 * Transient feedback toasts (bottom-right stack, rendered by the root App
 * component). Callers localize the message themselves.
 */
@Injectable({ providedIn: 'root' })
export class ToastStore {
  private readonly state = signal<ToastItem[]>([]);
  private nextId = 0;

  readonly items = this.state.asReadonly();

  /** Shows a toast that auto-dismisses; `message` is already localized. */
  push(message: string, variant: ToastVariant = 'success'): void {
    const id = this.nextId++;
    this.state.update((items) => [...items, { id, message, variant }]);
    setTimeout(() => {
      this.state.update((items) => items.filter((toast) => toast.id !== id));
    }, TOAST_DURATION_MS);
  }
}

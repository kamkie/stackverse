import { effect, signal, type Signal } from '@angular/core';

/**
 * The given signal's value, updated only after it has been stable for
 * `delayMs`. Registers an effect — create in an injection context.
 */
export function debounced<T>(source: Signal<T>, delayMs: number): Signal<T> {
  const out = signal(source());
  effect((onCleanup) => {
    const value = source();
    const timer = setTimeout(() => out.set(value), delayMs);
    onCleanup(() => clearTimeout(timer));
  });
  return out.asReadonly();
}

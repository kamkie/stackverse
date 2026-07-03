import {
  afterNextRender,
  Component,
  ElementRef,
  input,
  output,
  viewChild,
} from '@angular/core';

/**
 * Modal dialog on top of the native `<dialog>` element. Render it
 * conditionally (`@if`) — removal from the DOM is what closes it.
 */
@Component({
  selector: 'sv-dialog',
  template: `
    <dialog
      #dlg
      class="sv-dialog"
      [attr.data-ctx]="ctx() || null"
      (close)="closed.emit()"
    >
      <h2 class="sv-dialog-title">{{ title() }}</h2>
      <ng-content />
    </dialog>
  `,
})
export class Dialog {
  readonly title = input.required<string>();
  /**
   * Entity the dialog acts on, as `<type>:<id>` (e.g. `report:123`) — dialogs
   * render outside the row that opened them, so the dev action log
   * (src/app/dev/log-user-actions.ts) needs the context restated here.
   */
  readonly ctx = input<string>();
  /** Fired on native close (Esc) — the host should stop rendering the dialog. */
  readonly closed = output<void>();

  private readonly dlg = viewChild.required<ElementRef<HTMLDialogElement>>('dlg');

  constructor() {
    afterNextRender(() => {
      const dialog = this.dlg().nativeElement;
      if (dialog.open) return;
      // jsdom has no showModal; falling back to the open attribute keeps the
      // dialog testable.
      if (typeof dialog.showModal === 'function') dialog.showModal();
      else dialog.setAttribute('open', '');
    });
  }
}

import { Component, inject, input } from '@angular/core';
import type { FieldError } from '../api/types';
import { I18n } from '../i18n/i18n';

/**
 * The display text for an RFC 9457 field error: localized client-side via its
 * `messageKey` (the `validation.*` keys are in the bundle), falling back to
 * the server-localized `message`.
 */
export function localizeFieldError(error: FieldError, t: (key: string) => string): string {
  const localized = t(error.messageKey);
  // t() falls back to the key's last segment when the bundle lacks the key;
  // in that case the server-localized message is the better text.
  const keyFallback = error.messageKey.slice(error.messageKey.lastIndexOf('.') + 1);
  return localized === keyFallback ? error.message : localized;
}

/**
 * Form field with a label and an optional RFC 9457 field error. The caller
 * projects a single control and gives it the same id as `forId`, which wires
 * up the label association.
 */
@Component({
  selector: 'sv-field',
  template: `
    <div class="sv-field" [class.is-invalid]="!!error()">
      <label class="sv-label" [for]="forId()">{{ label() }}</label>
      <ng-content />
      @if (hint(); as hintText) {
        <span class="sv-field-hint" [id]="forId() + '-hint'">{{ hintText }}</span>
      }
      @if (error(); as fieldError) {
        <span class="sv-field-error" [id]="forId() + '-error'">
          {{ localize(fieldError) }}
        </span>
      }
    </div>
  `,
})
export class Field {
  private readonly i18n = inject(I18n);

  readonly label = input.required<string>();
  readonly forId = input.required<string>();
  readonly error = input<FieldError | undefined>();
  readonly hint = input<string>();

  protected localize(error: FieldError): string {
    return localizeFieldError(error, this.i18n.t);
  }
}

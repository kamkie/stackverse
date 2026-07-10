import { LitElement, html, nothing } from "lit";
import type { PropertyValues } from "lit";
import { unsafeHTML } from "lit/directives/unsafe-html.js";

export class StackverseDialogElement extends LitElement {
  static override properties = {
    markup: { attribute: false },
  };

  declare markup: string;

  constructor() {
    super();
    this.markup = "";
  }

  override createRenderRoot(): HTMLElement {
    return this;
  }

  override render() {
    return this.markup ? html`${unsafeHTML(this.markup)}` : nothing;
  }

  protected override updated(changedProperties: PropertyValues<this>): void {
    if (!changedProperties.has("markup")) return;
    const dialog = this.querySelector<HTMLDialogElement>("dialog.sv-dialog");
    if (!dialog) return;

    if (typeof dialog.showModal === "function" && !dialog.open) {
      dialog.showModal();
    } else {
      dialog.setAttribute("open", "");
    }
    dialog.addEventListener(
      "close",
      () => {
        this.dispatchEvent(
          new CustomEvent("stackverse-dialog-close", {
            bubbles: true,
            composed: true,
          }),
        );
      },
      { once: true },
    );
  }
}

customElements.define("stackverse-dialog", StackverseDialogElement);

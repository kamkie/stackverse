import type { JSX } from "solid-js";
import { Show } from "solid-js";

interface Props {
  label: string;
  hint?: string;
  error?: string;
  children: JSX.Element;
}

export default function Field(props: Props) {
  return (
    <label class={`sv-field${props.error ? " is-invalid" : ""}`}>
      <span class="sv-label">{props.label}</span>
      {props.children}
      <Show when={props.hint}>
        {(hint) => <span class="sv-field-hint">{hint()}</span>}
      </Show>
      <Show when={props.error}>
        {(error) => <span class="sv-field-error">{error()}</span>}
      </Show>
    </label>
  );
}

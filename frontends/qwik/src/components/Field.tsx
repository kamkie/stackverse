import { Slot, component$ } from "@builder.io/qwik";

interface Props {
  label: string;
  hint?: string;
  error?: string;
}

export default component$<Props>((props) => {
  return (
    <label class={`sv-field${props.error ? " is-invalid" : ""}`}>
      <span class="sv-label">{props.label}</span>
      <Slot />
      {props.hint ? <span class="sv-field-hint">{props.hint}</span> : null}
      {props.error ? <span class="sv-field-error">{props.error}</span> : null}
    </label>
  );
});

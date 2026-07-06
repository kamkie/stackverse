import { component$ } from "@builder.io/qwik";

export interface Toast {
  id: number;
  message: string;
  tone: "success" | "danger";
}

export default component$<{ toasts?: Toast[] }>((props) => {
  return (
    <div class="sv-toast-region" role="status" aria-live="polite">
      {(props.toasts ?? []).map((toast) => (
        <div
          key={toast.id}
          class={`sv-toast ${toast.tone === "success" ? "sv-toast--success" : "sv-toast--danger"}`}
        >
          {toast.message}
        </div>
      ))}
    </div>
  );
});

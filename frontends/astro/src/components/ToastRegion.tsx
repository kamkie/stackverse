import { For } from "solid-js";

export interface Toast {
  id: number;
  message: string;
  tone: "success" | "danger";
}

export default function ToastRegion(props: { toasts?: Toast[] }) {
  return (
    <div class="sv-toast-region" role="status" aria-live="polite">
      <For each={props.toasts ?? []}>
        {(toast) => (
          <div class={`sv-toast ${toast.tone === "success" ? "sv-toast--success" : "sv-toast--danger"}`}>
            {toast.message}
          </div>
        )}
      </For>
    </div>
  );
}

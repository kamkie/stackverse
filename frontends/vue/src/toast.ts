import { ref } from "vue";

export interface Toast {
  id: number;
  message: string;
  tone: "success" | "danger";
}

let nextToastId = 1;
export const toasts = ref<Toast[]>([]);

export function showToast(message: string, tone: Toast["tone"] = "success"): void {
  const toast = { id: nextToastId++, message, tone };
  toasts.value = [...toasts.value, toast];
  window.setTimeout(() => {
    toasts.value = toasts.value.filter((item) => item.id !== toast.id);
  }, 3500);
}

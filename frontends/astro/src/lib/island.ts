import { createSignal, onCleanup, onMount } from "solid-js";
import { initializeClient } from "./initializeClient";
import { i18n } from "./i18n";
import { expireSession, session } from "./session";
export type ToastFn = (message: string, tone?: "success" | "danger") => void;

interface Toast {
  id: number;
  message: string;
  tone: "success" | "danger";
}

export function useIsland() {
  const [toasts, setToasts] = createSignal<Toast[]>([]);
  let toastId = 0;

  function toast(message: string, tone: "success" | "danger" = "success") {
    const item = { id: ++toastId, message, tone };
    setToasts((current) => [...current, item]);
    window.setTimeout(
      () =>
        setToasts((current) =>
          current.filter((candidate) => candidate.id !== item.id),
        ),
      3500,
    );
  }

  onMount(() => {
    const onUnauthorized = () => {
      expireSession();
      location.assign("/feed");
    };

    window.addEventListener("stackverse:unauthorized", onUnauthorized);
    onCleanup(() =>
      window.removeEventListener("stackverse:unauthorized", onUnauthorized),
    );
    void initializeClient();
  });

  return {
    ready: () => i18n().ready && session() !== null,
    toast,
    toasts,
  };
}

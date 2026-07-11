import { createSignal, onCleanup, onMount, Show, type JSX } from "solid-js";
import { initializeClient } from "../lib/initializeClient";
import { i18n } from "../lib/i18n";
import { expireSession, isAdmin, isModerator, me, session } from "../lib/session";
import ToastRegion, { type Toast } from "./ToastRegion";

export type ToastFn = (message: string, tone?: "success" | "danger") => void;

interface Props {
  children: (toast: ToastFn) => JSX.Element;
  fallback?: (toast: ToastFn) => JSX.Element;
  requiresAuth?: boolean;
  requiredRole?: "moderator" | "admin";
}

export default function ClientPage(props: Props) {
  const [toasts, setToasts] = createSignal<Toast[]>([]);
  let toastId = 0;

  function showToast(message: string, tone: "success" | "danger" = "success") {
    const toast = { id: ++toastId, message, tone };
    setToasts((current) => [...current, toast]);
    window.setTimeout(() => setToasts((current) => current.filter((item) => item.id !== toast.id)), 3500);
  }

  function onUnauthorized() {
    expireSession();
    location.assign("/feed");
  }

  onMount(() => {
    window.addEventListener("stackverse:unauthorized", onUnauthorized);
    onCleanup(() => window.removeEventListener("stackverse:unauthorized", onUnauthorized));
    void initializeClient();
  });

  function content() {
    if (props.requiredRole && !isModerator(me())) return <div class="sv-alert sv-alert--danger" role="alert">403</div>;
    if (props.requiredRole === "admin" && !isAdmin(me())) return <div class="sv-alert sv-alert--danger" role="alert">403</div>;
    if (props.requiresAuth && !session()?.authenticated) {
      return props.fallback?.(showToast) ?? <div class="sv-alert sv-alert--danger" role="alert">401</div>;
    }
    return props.children(showToast);
  }

  return (
    <Show when={i18n().ready && session() !== null} fallback={<div class="sv-loading"><span class="sv-spinner" /></div>}>
      {content()}
      <ToastRegion toasts={toasts()} />
    </Show>
  );
}

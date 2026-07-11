import { onMount, Show } from "solid-js";
import { initializeClient } from "../../lib/initializeClient";
import { i18n, m } from "../../lib/i18n";
import { isModerator, me, session } from "../../lib/session";

interface Props {
  currentPath: string;
}

export default function PrimaryNavigation(props: Props) {
  const linkClass = (path: string, admin = false) => {
    const active = admin
      ? props.currentPath.startsWith("/admin")
      : props.currentPath === path;
    return `sv-nav-link${active ? " is-active" : ""}`;
  };

  onMount(() => void initializeClient());

  return (
    <nav class="sv-nav" aria-label="Primary">
      <Show when={session()?.authenticated}>
        <a href="/bookmarks" class={linkClass("/bookmarks")}>
          {m(i18n(), "ui.nav.my-bookmarks")}
        </a>
        <a href="/reports" class={linkClass("/reports")}>
          {m(i18n(), "ui.nav.my-reports")}
        </a>
      </Show>
      <a href="/feed" class={linkClass("/feed")}>
        {m(i18n(), "ui.nav.public-feed")}
      </a>
      <Show when={isModerator(me())}>
        <a href="/admin" class={linkClass("/admin", true)}>
          {m(i18n(), "ui.nav.admin")}
        </a>
      </Show>
    </nav>
  );
}

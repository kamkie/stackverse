import { onMount, Show } from "solid-js";
import { initializeClient } from "../../lib/initializeClient";
import { i18n, m } from "../../lib/i18n";
import { isAdmin, me } from "../../lib/session";

interface Props {
  currentPath: string;
}

export default function AdminNavigation(props: Props) {
  onMount(() => void initializeClient());
  const linkClass = (path: string) =>
    `sv-nav-link${props.currentPath === path ? " is-active" : ""}`;
  return (
    <aside class="sv-sidebar">
      <h2 class="sv-sidebar-title">{m(i18n(), "ui.nav.admin")}</h2>
      <nav class="sv-nav sv-nav--vertical" aria-label="Admin">
        <a href="/admin" class={linkClass("/admin")}>
          {m(i18n(), "ui.admin.dashboard")}
        </a>
        <a href="/admin/reports" class={linkClass("/admin/reports")}>
          {m(i18n(), "ui.admin.reports")}
        </a>
        <Show when={isAdmin(me())}>
          <a href="/admin/users" class={linkClass("/admin/users")}>
            {m(i18n(), "ui.admin.users")}
          </a>
          <a href="/admin/audit" class={linkClass("/admin/audit")}>
            {m(i18n(), "ui.admin.audit")}
          </a>
          <a href="/admin/messages" class={linkClass("/admin/messages")}>
            {m(i18n(), "ui.admin.messages")}
          </a>
        </Show>
      </nav>
    </aside>
  );
}

import { render } from "solid-js/web";
import type { JSX } from "solid-js";

export async function mountPage(page: () => JSX.Element): Promise<void> {
  if (import.meta.env.DEV) {
    const { forwardConsoleToDevServer } = await import("../dev/forwardConsoleToDevServer");
    forwardConsoleToDevServer();
    const { logUserActions } = await import("../dev/logUserActions");
    logUserActions();
  }
  const target = document.getElementById("app");
  if (!target) throw new Error("#app not found");
  render(page, target);
}

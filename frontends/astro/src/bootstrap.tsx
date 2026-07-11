import { render } from "solid-js/web";
import App, { type PageId } from "./App";

async function bootstrap() {
  if (import.meta.env.DEV) {
    const { forwardConsoleToDevServer } = await import(
      "./dev/forwardConsoleToDevServer"
    );
    forwardConsoleToDevServer();
    const { logUserActions } = await import("./dev/logUserActions");
    logUserActions();
  }

  const target = document.getElementById("app");
  if (!target) throw new Error("#app not found");
  const page = target.dataset.page as PageId | undefined;
  if (!page) throw new Error("#app[data-page] not found");
  render(() => <App page={page} />, target);
}

void bootstrap();

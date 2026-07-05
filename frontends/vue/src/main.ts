// The shared design is part of the contract - consumed verbatim from
// spec/design, never copied or overridden (see frontends/README.md).
import "../../../spec/design/tokens.css";
import "../../../spec/design/stackverse.css";

import { createApp } from "vue";
import App from "./App.vue";
import { router } from "./router";

async function bootstrap() {
  if (import.meta.env.DEV) {
    const { forwardConsoleToDevServer } = await import("./dev/forwardConsoleToDevServer");
    forwardConsoleToDevServer();
    const { logUserActions } = await import("./dev/logUserActions");
    logUserActions();
  }

  if (import.meta.env.DEV && import.meta.env["VITE_API_MOCK"] !== "false") {
    const { worker } = await import("./mocks/browser");
    await worker.start({ onUnhandledRequest: "bypass" });
  }

  createApp(App).use(router).mount("#app");
}

void bootstrap();

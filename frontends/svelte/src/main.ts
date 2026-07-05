// The shared design is part of the contract — consumed verbatim from
// spec/design, never copied or overridden (see frontends/README.md).
import "../../../spec/design/tokens.css";
import "../../../spec/design/stackverse.css";

import { mount } from "svelte";
import App from "./App.svelte";

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
  mount(App, { target });
}

void bootstrap();

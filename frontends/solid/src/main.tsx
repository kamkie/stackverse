// The shared design is part of the contract - consumed verbatim from
// spec/design, never copied or overridden (see frontends/README.md).
import "../../../spec/design/tokens.css";
import "../../../spec/design/stackverse.css";

import { render } from "solid-js/web";
import App from "./App";

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
  render(() => <App />, target);
}

void bootstrap();

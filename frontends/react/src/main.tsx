// The shared design is part of the contract — consumed verbatim from
// spec/design, never copied or overridden (see frontends/README.md).
import "../../../spec/design/tokens.css";
import "../../../spec/design/stackverse.css";

import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App";

async function bootstrap() {
  // In dev, mirror console output and uncaught errors to the dev server so
  // browser logs show up in the terminal and dev-server.log.
  if (import.meta.env.DEV) {
    const { forwardConsoleToDevServer } = await import(
      "./dev/forwardConsoleToDevServer"
    );
    forwardConsoleToDevServer();
  }

  // Mock the API in dev unless explicitly disabled (VITE_API_MOCK=false lets
  // the Vite proxy forward /api and /auth to a real gateway on :8000).
  if (import.meta.env.DEV && import.meta.env["VITE_API_MOCK"] !== "false") {
    const { worker } = await import("./mocks/browser");
    await worker.start({ onUnhandledRequest: "bypass" });
  }

  const container = document.getElementById("root");
  if (!container) throw new Error("#root not found");
  createRoot(container).render(
    <StrictMode>
      <App />
    </StrictMode>,
  );
}

void bootstrap();

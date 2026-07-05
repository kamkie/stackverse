import { setupWorker } from "msw/browser";
import {
  handlers,
  performMockLogin,
  restorePersistedSession,
} from "./handlers";

// A full-page navigation to /auth/login cannot be answered by the service
// worker (MSW ignores document requests). The Vite dev server serves the SPA
// for that URL in mock mode, and the fake OIDC dance completes here.
if (location.pathname === "/auth/login") {
  performMockLogin();
  history.replaceState(null, "", "/");
} else {
  restorePersistedSession();
}

export const worker = setupWorker(...handlers);

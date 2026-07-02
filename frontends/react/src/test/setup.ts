import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { setupServer } from "msw/node";
import { afterAll, afterEach, beforeAll } from "vitest";
import { resetDb } from "../mocks/db";
import { handlers } from "../mocks/handlers";
import { setCurrentUser } from "../mocks/state";

/** Same contract-derived handlers as the dev worker, on MSW's node server. */
export const server = setupServer(...handlers);

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));

afterEach(() => {
  cleanup();
  server.resetHandlers();
  resetDb();
  setCurrentUser(null);
  localStorage.clear();
  sessionStorage.clear();
});

afterAll(() => server.close());

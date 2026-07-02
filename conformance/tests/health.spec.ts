// Operational probes (spec/openapi.yaml `meta` tag): anonymous, no gateway.
import { expect, test } from "./fixtures";

test("healthz reports liveness without authentication", async ({ anon }) => {
  const response = await anon.get("/healthz");
  expect(response.status()).toBe(200);
});

test("readyz reports readiness when the database is reachable", async ({ anon }) => {
  const response = await anon.get("/readyz");
  expect(response.status()).toBe(200);
});

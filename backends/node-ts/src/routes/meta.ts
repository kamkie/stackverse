import type { FastifyInstance } from "fastify";
import { pool } from "../db.js";
import { logEvent, logger } from "../logging.js";
import { requireCaller, toMeResponse } from "../auth.js";

/** Liveness/readiness for the container runtime; not proxied by the gateway. */
export function registerMetaRoutes(app: FastifyInstance): void {
  app.get("/api/v1/me", async (request) => toMeResponse(requireCaller(request)));

  app.get("/healthz", async () => ({ status: "up" }));

  // the transition is signal, the individual probe is noise (docs/LOGGING.md §5)
  let wasReady = true;
  app.get("/readyz", async (_request, reply) => {
    const startedAt = Date.now();
    try {
      await pool.query("select 1");
      if (!wasReady) {
        wasReady = true;
        logger.info("Readiness restored: database reachable again");
      }
      return { status: "ready" };
    } catch (error) {
      if (wasReady) {
        wasReady = false;
        logEvent("warn", "dependency_call_failed", "failure", "Readiness lost: database unreachable", {
          dependency: "postgres",
          duration_ms: Date.now() - startedAt,
          error_code: (error as { code?: string }).code ?? "connection_error",
        });
      }
      return reply.code(503).send({ status: "unavailable" });
    }
  });
}

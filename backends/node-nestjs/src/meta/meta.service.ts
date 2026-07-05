import { Injectable } from "@nestjs/common";
import type { FastifyReply, FastifyRequest } from "fastify";
import { pool } from "../db.js";
import { logEvent, logger } from "../logging.js";
import { requireCaller, toMeResponse } from "../auth.js";

@Injectable()
export class MetaService {
  private wasReady = true;

  async me(request: FastifyRequest) {
    return toMeResponse(requireCaller(request));
  }

  async healthz(): Promise<{ status: "up" }> {
    return { status: "up" };
  }

  // the transition is signal, the individual probe is noise (docs/LOGGING.md §5)
  async readyz(reply: FastifyReply) {
    const startedAt = Date.now();
    try {
      await pool.query("select 1");
      if (!this.wasReady) {
        this.wasReady = true;
        logger.info("Readiness restored: database reachable again");
      }
      return { status: "ready" };
    } catch (error) {
      if (this.wasReady) {
        this.wasReady = false;
        logEvent("warn", "dependency_call_failed", "failure", "Readiness lost: database unreachable", {
          dependency: "postgres",
          duration_ms: Date.now() - startedAt,
          error_code: (error as { code?: string }).code ?? "connection_error",
        });
      }
      reply.code(503);
      return { status: "unavailable" };
    }
  }
}

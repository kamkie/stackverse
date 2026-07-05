import { NestFactory } from "@nestjs/core";
import { FastifyAdapter, type NestFastifyApplication } from "@nestjs/platform-fastify";
import { AppModule } from "./app.module.js";
import { logger } from "./logging.js";

export async function buildApp(): Promise<NestFastifyApplication> {
  const adapter = new FastifyAdapter({
    loggerInstance: logger,
    // OTEL server spans are the per-request record; access logs would only add
    // probe noise (docs/LOGGING.md §5)
    disableRequestLogging: true,
  });

  // No request rate limiting / throttling is wired here on purpose: it is out of
  // scope for Stackverse backends (docs/SPEC.md "Out of scope (on purpose)",
  // docs/INTENT.md non-goals). In this architecture throttling belongs at the
  // edge (gateway / operator), like the platform concerns in docs/LOGGING.md
  // Appendix A — not duplicated into every stateless backend.
  return NestFactory.create<NestFastifyApplication>(AppModule, adapter, { logger: false });
}

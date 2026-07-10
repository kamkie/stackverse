import { NestFactory } from "@nestjs/core";
import { FastifyAdapter, type NestFastifyApplication } from "@nestjs/platform-fastify";
import type { RawRequestDefaultExpression, RawServerDefault } from "fastify";
import { AppModule } from "./app.module.js";
import { registerFastifyAuth } from "./auth.js";
import { logger } from "./logging.js";
import { sendProblemForError } from "./problem.filter.js";
import { contractValidationPipe } from "./validation.pipe.js";

export async function buildApp(): Promise<NestFastifyApplication> {
  const adapter = new FastifyAdapter<RawServerDefault, RawRequestDefaultExpression<RawServerDefault>>({
    loggerInstance: logger,
    // OTEL server spans are the per-request record; access logs would only add
    // probe noise (docs/LOGGING.md §5)
    disableRequestLogging: true,
  });
  const fastify = adapter.getInstance();
  registerFastifyAuth(fastify);

  // No request rate limiting / throttling is wired here on purpose: it is out of
  // scope for Stackverse backends (docs/SPEC.md "Out of scope (on purpose)",
  // docs/INTENT.md non-goals). In this architecture throttling belongs at the
  // edge (gateway / operator), like the platform concerns in docs/LOGGING.md
  // Appendix A — not duplicated into every stateless backend.
  const app = await NestFactory.create<NestFastifyApplication>(AppModule, adapter, { logger: false });
  app.useGlobalPipes(contractValidationPipe());

  // Fastify content parser failures (malformed JSON, oversized body) occur
  // before Nest guards/filters run, so bridge adapter-level failures into the
  // same RFC 9457 renderer used by the Nest exception filter.
  fastify.setErrorHandler(async (error, request, reply) => sendProblemForError(error, request, reply));

  return app;
}

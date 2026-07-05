import { Controller, Get, Req, Res } from "@nestjs/common";
import type { FastifyReply, FastifyRequest } from "fastify";
import { MetaService } from "./meta.service.js";

@Controller()
export class MetaController {
  constructor(private readonly meta: MetaService) {}

  @Get("/api/v1/me")
  async me(@Req() request: FastifyRequest) {
    return this.meta.me(request);
  }

  @Get("/healthz")
  async healthz() {
    return this.meta.healthz();
  }

  @Get("/readyz")
  async readyz(@Res({ passthrough: true }) reply: FastifyReply) {
    return this.meta.readyz(reply);
  }
}

import { Controller, Get, Req, Res } from "@nestjs/common";
import type { FastifyReply, FastifyRequest } from "fastify";
import { StatsService } from "./stats.service.js";

@Controller()
export class StatsController {
  constructor(private readonly stats: StatsService) {}

  @Get("/api/v1/admin/stats")
  async get(@Req() request: FastifyRequest, @Res() reply: FastifyReply) {
    return this.stats.get(request, reply);
  }
}

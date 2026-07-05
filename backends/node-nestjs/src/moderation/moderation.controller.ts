import { Body, Controller, Delete, Get, Param, Post, Put, Req, Res } from "@nestjs/common";
import type { FastifyReply, FastifyRequest } from "fastify";
import { ModerationService } from "./moderation.service.js";

@Controller()
export class ModerationController {
  constructor(private readonly moderation: ModerationService) {}

  @Post("/api/v1/bookmarks/:id/reports")
  async reportBookmark(
    @Req() request: FastifyRequest,
    @Res() reply: FastifyReply,
    @Param("id") id: string,
    @Body() body: unknown,
  ) {
    return this.moderation.reportBookmark(request, reply, id, body);
  }

  @Get("/api/v1/reports")
  async listMyReports(@Req() request: FastifyRequest) {
    return this.moderation.listMyReports(request);
  }

  @Put("/api/v1/reports/:id")
  async updateMyReport(@Req() request: FastifyRequest, @Param("id") id: string, @Body() body: unknown) {
    return this.moderation.updateMyReport(request, id, body);
  }

  @Delete("/api/v1/reports/:id")
  async withdrawReport(@Req() request: FastifyRequest, @Res() reply: FastifyReply, @Param("id") id: string) {
    return this.moderation.withdrawReport(request, reply, id);
  }

  @Get("/api/v1/admin/reports")
  async listAdminReports(@Req() request: FastifyRequest) {
    return this.moderation.listAdminReports(request);
  }

  @Put("/api/v1/admin/reports/:id")
  async resolveReport(@Req() request: FastifyRequest, @Param("id") id: string, @Body() body: unknown) {
    return this.moderation.resolveReport(request, id, body);
  }

  @Put("/api/v1/admin/bookmarks/:id/status")
  async setBookmarkStatus(@Req() request: FastifyRequest, @Param("id") id: string, @Body() body: unknown) {
    return this.moderation.setBookmarkStatus(request, id, body);
  }
}

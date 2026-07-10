import { Controller, Get, Req } from "@nestjs/common";
import type { FastifyRequest } from "fastify";
import { Roles } from "../auth.js";
import { AuditLogService } from "./audit-log.service.js";

@Controller()
@Roles("admin")
export class AuditLogController {
  constructor(private readonly auditLog: AuditLogService) {}

  @Get("/api/v1/admin/audit-log")
  async list(@Req() request: FastifyRequest) {
    return this.auditLog.list(request);
  }
}

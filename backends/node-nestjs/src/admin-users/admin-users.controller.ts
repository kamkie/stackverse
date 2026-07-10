import { Body, Controller, Get, Param, Put, Req } from "@nestjs/common";
import type { FastifyRequest } from "fastify";
import { Roles } from "../auth.js";
import { UserStatusBodyDto } from "./user-status.dto.js";
import { AdminUsersService } from "./admin-users.service.js";

@Controller()
@Roles("admin")
export class AdminUsersController {
  constructor(private readonly users: AdminUsersService) {}

  @Get("/api/v1/admin/users")
  async list(@Req() request: FastifyRequest) {
    return this.users.list(request);
  }

  @Get("/api/v1/admin/users/:username")
  async get(@Req() request: FastifyRequest, @Param("username") username: string) {
    return this.users.get(request, username);
  }

  @Put("/api/v1/admin/users/:username/status")
  async setStatus(
    @Req() request: FastifyRequest,
    @Param("username") username: string,
    @Body() body: UserStatusBodyDto,
  ) {
    return this.users.setStatus(request, username, body);
  }
}

import { Body, Controller, Get, Param, Put, Req } from "@nestjs/common";
import type { FastifyRequest } from "fastify";
import { AdminUsersService } from "./admin-users.service.js";

@Controller()
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
  async setStatus(@Req() request: FastifyRequest, @Param("username") username: string, @Body() body: unknown) {
    return this.users.setStatus(request, username, body);
  }
}

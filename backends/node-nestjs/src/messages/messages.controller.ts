import { Body, Controller, Delete, Get, Param, Post, Put, Req, Res } from "@nestjs/common";
import type { FastifyReply, FastifyRequest } from "fastify";
import { MessagesService } from "./messages.service.js";

@Controller()
export class MessagesController {
  constructor(private readonly messages: MessagesService) {}

  @Get("/api/v1/messages")
  async list(@Req() request: FastifyRequest, @Res() reply: FastifyReply) {
    return this.messages.list(request, reply);
  }

  @Get("/api/v1/messages/bundle")
  async bundle(@Req() request: FastifyRequest, @Res() reply: FastifyReply) {
    return this.messages.bundle(request, reply);
  }

  @Get("/api/v1/messages/:id")
  async get(@Req() request: FastifyRequest, @Res() reply: FastifyReply, @Param("id") id: string) {
    return this.messages.get(request, reply, id);
  }

  @Post("/api/v1/messages")
  async create(@Req() request: FastifyRequest, @Res() reply: FastifyReply, @Body() body: unknown) {
    return this.messages.create(request, reply, body);
  }

  @Put("/api/v1/messages/:id")
  async update(@Req() request: FastifyRequest, @Param("id") id: string, @Body() body: unknown) {
    return this.messages.update(request, id, body);
  }

  @Delete("/api/v1/messages/:id")
  async delete(@Req() request: FastifyRequest, @Res() reply: FastifyReply, @Param("id") id: string) {
    return this.messages.delete(request, reply, id);
  }
}

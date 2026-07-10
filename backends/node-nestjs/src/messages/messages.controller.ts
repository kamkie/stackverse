import { Body, Controller, Delete, Get, Param, Post, Put, Req, Res } from "@nestjs/common";
import type { FastifyReply, FastifyRequest } from "fastify";
import { Public, Roles } from "../auth.js";
import { MessageBodyDto } from "./message.dto.js";
import { MessagesService } from "./messages.service.js";

@Controller()
export class MessagesController {
  constructor(private readonly messages: MessagesService) {}

  @Get("/api/v1/messages")
  @Public()
  async list(@Req() request: FastifyRequest, @Res() reply: FastifyReply) {
    return this.messages.list(request, reply);
  }

  @Get("/api/v1/messages/bundle")
  @Public()
  async bundle(@Req() request: FastifyRequest, @Res() reply: FastifyReply) {
    return this.messages.bundle(request, reply);
  }

  @Get("/api/v1/messages/:id")
  @Public()
  async get(@Req() request: FastifyRequest, @Res() reply: FastifyReply, @Param("id") id: string) {
    return this.messages.get(request, reply, id);
  }

  @Post("/api/v1/messages")
  @Roles("admin")
  async create(@Req() request: FastifyRequest, @Res() reply: FastifyReply, @Body() body: MessageBodyDto) {
    return this.messages.create(request, reply, body);
  }

  @Put("/api/v1/messages/:id")
  @Roles("admin")
  async update(@Req() request: FastifyRequest, @Param("id") id: string, @Body() body: MessageBodyDto) {
    return this.messages.update(request, id, body);
  }

  @Delete("/api/v1/messages/:id")
  @Roles("admin")
  async delete(@Req() request: FastifyRequest, @Res() reply: FastifyReply, @Param("id") id: string) {
    return this.messages.delete(request, reply, id);
  }
}

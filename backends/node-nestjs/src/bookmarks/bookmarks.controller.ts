import { Body, Controller, Delete, Get, Param, Post, Put, Req, Res } from "@nestjs/common";
import type { FastifyReply, FastifyRequest } from "fastify";
import { BookmarkBodyDto } from "./bookmark.dto.js";
import { BookmarksService } from "./bookmarks.service.js";

@Controller()
export class BookmarksController {
  constructor(private readonly bookmarks: BookmarksService) {}

  @Get("/api/v1/bookmarks")
  async listV1(@Req() request: FastifyRequest, @Res({ passthrough: true }) reply: FastifyReply) {
    return this.bookmarks.listV1(request, reply);
  }

  @Get("/api/v2/bookmarks")
  async listV2(@Req() request: FastifyRequest) {
    return this.bookmarks.listV2(request);
  }

  @Post("/api/v1/bookmarks")
  async create(@Req() request: FastifyRequest, @Res() reply: FastifyReply, @Body() body: BookmarkBodyDto) {
    return this.bookmarks.create(request, reply, body);
  }

  @Get("/api/v1/bookmarks/:id")
  async get(@Req() request: FastifyRequest, @Param("id") id: string) {
    return this.bookmarks.get(request, id);
  }

  @Put("/api/v1/bookmarks/:id")
  async update(@Req() request: FastifyRequest, @Param("id") id: string, @Body() body: BookmarkBodyDto) {
    return this.bookmarks.update(request, id, body);
  }

  @Delete("/api/v1/bookmarks/:id")
  async delete(@Req() request: FastifyRequest, @Res() reply: FastifyReply, @Param("id") id: string) {
    return this.bookmarks.delete(request, reply, id);
  }

  @Get("/api/v1/tags")
  async listTags(@Req() request: FastifyRequest) {
    return this.bookmarks.listTags(request);
  }
}

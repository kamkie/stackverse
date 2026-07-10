import { APP_FILTER, APP_GUARD } from "@nestjs/core";
import { Test } from "@nestjs/testing";
import { FastifyAdapter, type NestFastifyApplication } from "@nestjs/platform-fastify";
import type { FastifyReply, FastifyRequest } from "fastify";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("./i18n.js", async (importOriginal) => {
  const original = await importOriginal<typeof import("./i18n.js")>();
  return {
    ...original,
    localize: async (key: string) => key,
    resolveRequestLanguage: async () => "en",
  };
});

import { AdminUsersController } from "./admin-users/admin-users.controller.js";
import { AdminUsersService } from "./admin-users/admin-users.service.js";
import { AuthorizationGuard, BearerAuthGuard, registerFastifyAuth } from "./auth.js";
import { BookmarkBodyDto } from "./bookmarks/bookmark.dto.js";
import { BookmarksController } from "./bookmarks/bookmarks.controller.js";
import { BookmarksService } from "./bookmarks/bookmarks.service.js";
import { ProblemFilter, sendProblemForError } from "./problem.filter.js";
import { contractValidationPipe } from "./validation.pipe.js";

describe("Nest framework HTTP boundaries", () => {
  let app: NestFastifyApplication;
  let createBookmark: ReturnType<typeof vi.fn>;
  let setUserStatus: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    createBookmark = vi.fn(async (_request: FastifyRequest, reply: FastifyReply, body: BookmarkBodyDto) =>
      reply.code(201).send(body),
    );
    setUserStatus = vi.fn(async () => ({ username: "target", status: "active" }));
    const bookmarks = {
      create: createBookmark,
      listV1: vi.fn(async () => ({ items: [] })),
    };
    const module = await Test.createTestingModule({
      controllers: [BookmarksController, AdminUsersController],
      providers: [
        { provide: BookmarksService, useValue: bookmarks },
        { provide: AdminUsersService, useValue: { setStatus: setUserStatus } },
        { provide: APP_GUARD, useClass: BearerAuthGuard },
        { provide: APP_GUARD, useClass: AuthorizationGuard },
        { provide: APP_FILTER, useClass: ProblemFilter },
      ],
    }).compile();

    const adapter = new FastifyAdapter();
    const fastify = adapter.getInstance();
    registerFastifyAuth(fastify);
    // The production hook has already marked a token-less request as parsed;
    // this test-only hook injects a caller so the real Nest authorization guard
    // can exercise authenticated and role-protected routes without an IdP.
    fastify.addHook("onRequest", async (request) => {
      const username = request.headers["x-test-user"];
      if (typeof username !== "string") return;
      const roles = request.headers["x-test-roles"];
      request.caller = {
        username,
        roles: typeof roles === "string" ? roles.split(",").filter(Boolean) : [],
      };
    });
    fastify.setErrorHandler(async (error, request, reply) => sendProblemForError(error, request, reply));
    app = module.createNestApplication<NestFastifyApplication>(adapter, { logger: false });
    app.useGlobalPipes(contractValidationPipe());
    await app.init();
    await fastify.ready();
  });

  afterEach(async () => app.close());

  it("keeps explicitly public reads anonymous-capable", async () => {
    const response = await app.inject({ method: "GET", url: "/api/v1/bookmarks?visibility=public" });

    expect(response.statusCode).toBe(200);
    expect(response.json()).toEqual({ items: [] });
  });

  it("runs DTO transformation through the controller", async () => {
    const response = await app.inject({
      method: "POST",
      url: "/api/v1/bookmarks",
      headers: { "x-test-user": "demo" },
      payload: { url: " https://example.com ", title: " Example ", tags: [" Nest "] },
    });

    expect(response.statusCode).toBe(201);
    expect(response.json()).toMatchObject({
      url: "https://example.com",
      title: "Example",
      tags: ["nest"],
      visibility: "private",
    });
  });

  it("rejects an anonymous invalid body before the DTO pipe", async () => {
    const response = await app.inject({ method: "POST", url: "/api/v1/bookmarks", payload: { title: "x" } });

    expect(response.statusCode).toBe(401);
    expect(response.json()).toMatchObject({ status: 401, title: "Unauthorized" });
    expect(createBookmark).not.toHaveBeenCalled();
  });

  it("runs DTO failures after an authenticated caller boundary", async () => {
    const response = await app.inject({
      method: "POST",
      url: "/api/v1/bookmarks",
      headers: { "x-test-user": "demo" },
      payload: { title: "x" },
    });

    expect(response.statusCode).toBe(400);
    expect(response.headers["content-type"]).toContain("application/problem+json");
    expect(response.json()).toMatchObject({
      status: 400,
      title: "Bad Request",
      errors: [
        {
          field: "url",
          messageKey: "validation.url.required",
          message: "validation.url.required",
        },
      ],
    });
    expect(createBookmark).not.toHaveBeenCalled();
  });

  it("runs role metadata before DTO validation", async () => {
    const wrongRole = await app.inject({
      method: "PUT",
      url: "/api/v1/admin/users/target/status",
      headers: { "x-test-user": "demo" },
      payload: { status: "blocked" },
    });

    expect(wrongRole.statusCode).toBe(403);
    expect(wrongRole.json()).toMatchObject({ status: 403, title: "Forbidden" });

    const authorized = await app.inject({
      method: "PUT",
      url: "/api/v1/admin/users/target/status",
      headers: { "x-test-user": "admin", "x-test-roles": "admin" },
      payload: { status: "blocked" },
    });

    expect(authorized.statusCode).toBe(400);
    expect(authorized.json()).toMatchObject({
      errors: [
        {
          field: "reason",
          messageKey: "validation.block.reason.required",
          message: "validation.block.reason.required",
        },
      ],
    });
    expect(setUserStatus).not.toHaveBeenCalled();
  });

  it("keeps invalid bearer rejection ahead of malformed JSON parsing", async () => {
    const response = await app.inject({
      method: "POST",
      url: "/api/v1/bookmarks",
      headers: { authorization: "Bearer not-a-jwt", "content-type": "application/json" },
      payload: "{",
    });

    expect(response.statusCode).toBe(401);
    expect(response.json()).toMatchObject({ status: 401, title: "Unauthorized" });
    expect(createBookmark).not.toHaveBeenCalled();
  });
});

import { APP_FILTER, APP_GUARD } from "@nestjs/core";
import { Test } from "@nestjs/testing";
import { FastifyAdapter, type NestFastifyApplication } from "@nestjs/platform-fastify";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("./i18n.js", async (importOriginal) => {
  const original = await importOriginal<typeof import("./i18n.js")>();
  return {
    ...original,
    localize: async (key: string) => key,
    resolveRequestLanguage: async () => "en",
  };
});

import { BearerAuthGuard, registerFastifyAuth } from "./auth.js";
import { BookmarksController } from "./bookmarks/bookmarks.controller.js";
import { BookmarksService } from "./bookmarks/bookmarks.service.js";
import { ProblemFilter, sendProblemForError } from "./problem.filter.js";
import { contractValidationPipe } from "./validation.pipe.js";

describe("Nest framework HTTP boundaries", () => {
  let app: NestFastifyApplication;

  beforeEach(async () => {
    const bookmarks = {
      create: vi.fn(async (_request, reply, body) => reply.code(201).send(body)),
      listV1: vi.fn(async () => ({ items: [] })),
    };
    const module = await Test.createTestingModule({
      controllers: [BookmarksController],
      providers: [
        { provide: BookmarksService, useValue: bookmarks },
        { provide: APP_GUARD, useClass: BearerAuthGuard },
        { provide: APP_FILTER, useClass: ProblemFilter },
      ],
    }).compile();

    const adapter = new FastifyAdapter();
    const fastify = adapter.getInstance();
    registerFastifyAuth(fastify);
    fastify.setErrorHandler(async (error, request, reply) => sendProblemForError(error, request, reply));
    app = module.createNestApplication<NestFastifyApplication>(adapter, { logger: false });
    app.useGlobalPipes(contractValidationPipe());
    await app.init();
    await fastify.ready();
  });

  afterEach(async () => app.close());

  it("runs DTO transformation through the controller", async () => {
    const response = await app.inject({
      method: "POST",
      url: "/api/v1/bookmarks",
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

  it("maps pipe failures through the RFC 9457 exception filter", async () => {
    const response = await app.inject({ method: "POST", url: "/api/v1/bookmarks", payload: { title: "x" } });

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
  });

  it("runs the bearer guard before controller dispatch", async () => {
    const response = await app.inject({
      method: "GET",
      url: "/api/v1/bookmarks?visibility=public",
      headers: { authorization: "Bearer not-a-jwt" },
    });

    expect(response.statusCode).toBe(401);
    expect(response.json()).toMatchObject({ status: 401, title: "Unauthorized" });
  });
});

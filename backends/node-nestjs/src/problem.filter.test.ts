import { BadRequestException, NotFoundException } from "@nestjs/common";
import { beforeEach, describe, expect, it, vi } from "vitest";

const { localizeMock, resolveRequestLanguageMock, logEventMock } = vi.hoisted(() => ({
  localizeMock: vi.fn(),
  resolveRequestLanguageMock: vi.fn(),
  logEventMock: vi.fn(),
}));

vi.mock("./i18n.js", () => ({
  localize: localizeMock,
  resolveRequestLanguage: resolveRequestLanguageMock,
}));
vi.mock("./logging.js", () => ({ logEvent: logEventMock }));

import { sendProblemForError } from "./problem.filter.js";
import { ConflictProblem, ValidationProblem } from "./problems.js";

const requestStub = () => ({
  query: { lang: "pl" },
  headers: { "accept-language": "en" },
  log: { error: vi.fn() },
});

const replyStub = () => {
  const reply = {
    statusCode: undefined as number | undefined,
    contentType: undefined as string | undefined,
    body: undefined as unknown,
    elapsedTime: 12.6,
    code(status: number) {
      this.statusCode = status;
      return this;
    },
    type(value: string) {
      this.contentType = value;
      return this;
    },
    send(body?: unknown) {
      this.body = body;
      return this;
    },
  };
  return reply;
};

beforeEach(() => {
  localizeMock.mockReset();
  resolveRequestLanguageMock.mockReset();
  logEventMock.mockReset();
});

describe("problem filter mappings", () => {
  it("localizes every validation field and logs only server-defined field names", async () => {
    resolveRequestLanguageMock.mockResolvedValueOnce("pl");
    localizeMock.mockResolvedValueOnce("Adres jest wymagany").mockResolvedValueOnce("Tytuł jest wymagany");
    const request = requestStub();
    const reply = replyStub();

    await sendProblemForError(
      new ValidationProblem([
        { field: "url", messageKey: "validation.url.required" },
        { field: "title", messageKey: "validation.title.required" },
      ]),
      request as never,
      reply as never,
    );

    expect(reply.statusCode).toBe(400);
    expect(reply.contentType).toBe("application/problem+json");
    expect(reply.body).toMatchObject({
      title: "Bad Request",
      detail: "Request validation failed.",
      errors: [
        { field: "url", messageKey: "validation.url.required", message: "Adres jest wymagany" },
        { field: "title", messageKey: "validation.title.required", message: "Tytuł jest wymagany" },
      ],
    });
    expect(resolveRequestLanguageMock).toHaveBeenCalledWith({ lang: "pl" }, "en");
    expect(logEventMock).toHaveBeenCalledWith(
      "info",
      "input_validation_failed",
      "failure",
      "Request validation failed",
      { error_code: "validation_failed", fields: "url,title" },
    );
  });

  it("resolves localized application problem details", async () => {
    resolveRequestLanguageMock.mockResolvedValueOnce("pl");
    localizeMock.mockResolvedValueOnce("Nie można opublikować ukrytej zakładki.");
    const reply = replyStub();

    await sendProblemForError(
      new ConflictProblem("fallback", "error.bookmark.hidden-publish"),
      requestStub() as never,
      reply as never,
    );

    expect(reply.body).toMatchObject({
      status: 409,
      title: "Conflict",
      detail: "Nie można opublikować ukrytej zakładki.",
    });
    expect(localizeMock).toHaveBeenCalledWith("error.bookmark.hidden-publish", "pl");
  });

  it("preserves framework HTTP statuses and extracts string or array details", async () => {
    const notFoundReply = replyStub();
    await sendProblemForError(new NotFoundException("No route"), requestStub() as never, notFoundReply as never);
    expect(notFoundReply.body).toMatchObject({ status: 404, title: "Not Found", detail: "No route" });

    const validationReply = replyStub();
    await sendProblemForError(
      new BadRequestException({ message: ["first", "second"] }),
      requestStub() as never,
      validationReply as never,
    );
    expect(validationReply.body).toMatchObject({ status: 400, detail: "first; second" });
  });

  it("maps Fastify parser 4xx errors without error-level logging", async () => {
    const reply = replyStub();
    await sendProblemForError(
      Object.assign(new Error("Request body is too large"), { statusCode: 413 }),
      requestStub() as never,
      reply as never,
    );

    expect(reply.body).toMatchObject({ status: 413, title: "Bad Request", detail: "Request body is too large" });
    expect(logEventMock).not.toHaveBeenCalled();
  });

  it("classifies socket and SQLSTATE connection failures as PostgreSQL dependency events", async () => {
    const error = Object.assign(new Error("database unavailable"), { code: "08006" });
    const request = requestStub();
    const reply = replyStub();

    await sendProblemForError(error, request as never, reply as never);

    expect(logEventMock).toHaveBeenCalledWith(
      "error",
      "dependency_call_failed",
      "failure",
      "PostgreSQL call failed during a request",
      {
        dependency: "postgres",
        duration_ms: 13,
        error_code: "08006",
        err: error,
      },
    );
    expect(request.log.error).not.toHaveBeenCalled();
    expect(reply.body).toEqual({
      type: "about:blank",
      title: "Internal Server Error",
      status: 500,
      detail: "An unexpected error occurred.",
    });
  });

  it("logs unexpected failures through the request logger and returns generic problem detail", async () => {
    const request = requestStub();
    const reply = replyStub();
    const error = new Error("secret internal failure detail");

    await sendProblemForError(error, request as never, reply as never);

    expect(request.log.error).toHaveBeenCalledWith({ err: error }, "Unhandled error");
    expect(logEventMock).not.toHaveBeenCalled();
    expect(reply.body).toMatchObject({ status: 500, detail: "An unexpected error occurred." });
  });
});

import { createHash } from "node:crypto";
import { describe, expect, it } from "vitest";
import { sendWithEtag } from "./etag.js";

const replyStub = () => {
  const reply = {
    statusCode: undefined as number | undefined,
    contentType: undefined as string | undefined,
    headers: {} as Record<string, string>,
    body: undefined as unknown,
    header(name: string, value: string) {
      this.headers[name] = value;
      return this;
    },
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

const expectedEtag = (payload: unknown) =>
  `"${createHash("sha256").update(JSON.stringify(payload)).digest("base64url")}"`;

describe("sendWithEtag (SPEC rules 10 + 19)", () => {
  it("sends a deterministic JSON body with ETag and no-cache headers", () => {
    const payload = { language: "en", messages: { "ui.save": "Save" } };
    const reply = replyStub();

    sendWithEtag({ headers: {} } as never, reply as never, payload);

    expect(reply.headers).toMatchObject({
      etag: expectedEtag(payload),
      "cache-control": "no-cache",
    });
    expect(reply.contentType).toBe("application/json; charset=utf-8");
    expect(reply.body).toBe(JSON.stringify(payload));
    expect(reply.statusCode).toBeUndefined();
  });

  it("returns 304 with an empty body when If-None-Match contains the current tag", () => {
    const payload = { totals: { users: 1 } };
    const etag = expectedEtag(payload);
    const reply = replyStub();

    sendWithEtag({ headers: { "if-none-match": `"old", ${etag}` } } as never, reply as never, payload);

    expect(reply.headers.etag).toBe(etag);
    expect(reply.statusCode).toBe(304);
    expect(reply.contentType).toBeUndefined();
    expect(reply.body).toBeUndefined();
  });

  it("ignores non-matching If-None-Match values", () => {
    const payload = { items: [] };
    const reply = replyStub();

    sendWithEtag({ headers: { "if-none-match": '"different"' } } as never, reply as never, payload);

    expect(reply.statusCode).toBeUndefined();
    expect(reply.body).toBe(JSON.stringify(payload));
  });
});

import Fastify from "fastify";
import { describe, expect, it } from "vitest";
import { sendWithEtag } from "./etag.js";

const payload = {
  language: "en",
  messages: {
    "ui.nav.bookmarks": "Bookmarks",
  },
};

const buildApp = () => {
  const app = Fastify({ logger: false });
  app.get("/bundle", (request, reply) => sendWithEtag(request, reply, payload));
  return app;
};

describe("sendWithEtag (SPEC rules 10 + 19)", () => {
  it("sends a deterministic JSON body with a strong ETag and no-cache revalidation", async () => {
    const app = buildApp();
    try {
      const response = await app.inject({ method: "GET", url: "/bundle" });

      expect(response.statusCode).toBe(200);
      expect(response.headers["etag"]).toMatch(/^"[A-Za-z0-9_-]+"$/);
      expect(response.headers["cache-control"]).toBe("no-cache");
      expect(response.headers["content-type"]).toContain("application/json");
      expect(response.body).toBe(JSON.stringify(payload));
    } finally {
      await app.close();
    }
  });

  it("returns 304 with an empty body when If-None-Match contains the current ETag", async () => {
    const app = buildApp();
    try {
      const first = await app.inject({ method: "GET", url: "/bundle" });
      const etag = String(first.headers["etag"]);

      const cached = await app.inject({
        method: "GET",
        url: "/bundle",
        headers: { "if-none-match": `"stale", ${etag}` },
      });

      expect(cached.statusCode).toBe(304);
      expect(cached.headers["etag"]).toBe(etag);
      expect(cached.headers["cache-control"]).toBe("no-cache");
      expect(cached.body).toBe("");
    } finally {
      await app.close();
    }
  });
});

// Runtime-managed messages (SPEC rules 7–12): public reads with ETag
// revalidation, admin-only writes, language resolution and en fallback.
import { expect, expectProblem, test, uid } from "./fixtures";
import type { APIRequestContext } from "@playwright/test";

interface Message {
  id: string;
  key: string;
  language: string;
  text: string;
  description?: string;
  createdAt: string;
  updatedAt: string;
}

interface Bundle {
  language: string;
  messages: Record<string, string>;
}

async function createMessage(
  admin: APIRequestContext,
  data: { key: string; language: string; text: string; description?: string },
): Promise<Message> {
  const response = await admin.post("/api/v1/messages", { data });
  expect(response.status(), await response.text()).toBe(201);
  return (await response.json()) as Message;
}

test("message reads are public and revalidate with ETag / 304", async ({ anon }) => {
  const first = await anon.get("/api/v1/messages");
  expect(first.status()).toBe(200);
  const etag = first.headers()["etag"];
  expect(etag).toBeTruthy();
  expect(first.headers()["cache-control"]).toContain("no-cache");

  const revalidated = await anon.get("/api/v1/messages", {
    headers: { "If-None-Match": etag ?? "" },
  });
  expect(revalidated.status()).toBe(304);
  expect(await revalidated.text()).toBe("");
});

test("the listing filters by exact key and language", async ({ anon, admin }) => {
  const key = `conformance.filter.${uid()}`;
  await createMessage(admin, { key, language: "en", text: "filter me" });
  const response = await anon.get(`/api/v1/messages?key=${key}&language=en`);
  const page = (await response.json()) as { items: Message[]; totalItems: number };
  expect(page.totalItems).toBe(1);
  expect(page.items[0]?.key).toBe(key);
});

test("a single message is publicly readable with ETag / 304 and 404s when unknown", async ({ anon, admin }) => {
  const created = await createMessage(admin, {
    key: `conformance.single.${uid()}`,
    language: "en",
    text: "single",
  });
  const read = await anon.get(`/api/v1/messages/${created.id}`);
  expect(read.status()).toBe(200);
  const etag = read.headers()["etag"];
  expect(etag).toBeTruthy();
  expect((await anon.get(`/api/v1/messages/${created.id}`, { headers: { "If-None-Match": etag ?? "" } })).status()).toBe(304);
  await expectProblem(await anon.get("/api/v1/messages/00000000-0000-0000-0000-000000000000"), 404);
});

test("bundle language resolution: lang parameter beats Accept-Language beats en", async ({ anon }) => {
  const byDefault = await anon.get("/api/v1/messages/bundle");
  expect(byDefault.headers()["content-language"]).toBe("en");
  expect(((await byDefault.json()) as Bundle).language).toBe("en");

  const byHeader = await anon.get("/api/v1/messages/bundle", {
    headers: { "Accept-Language": "pl, en;q=0.5" },
  });
  expect(byHeader.headers()["content-language"]).toBe("pl");

  const byParameter = await anon.get("/api/v1/messages/bundle?lang=pl", {
    headers: { "Accept-Language": "en" },
  });
  expect(byParameter.headers()["content-language"]).toBe("pl");

  // unsupported values fall down the chain instead of erroring (rule 8)
  const unsupported = await anon.get("/api/v1/messages/bundle?lang=zz");
  expect(unsupported.status()).toBe(200);
  expect(unsupported.headers()["content-language"]).toBe("en");
});

test("bundle keys missing in the resolved language fall back to their en text", async ({ anon, admin }) => {
  const key = `conformance.fallback.${uid()}`;
  await createMessage(admin, { key, language: "en", text: "english only" });
  const response = await anon.get("/api/v1/messages/bundle?lang=pl");
  const bundle = (await response.json()) as Bundle;
  expect(bundle.language).toBe("pl");
  expect(bundle.messages[key]).toBe("english only");
});

test("writes are admin-only", async ({ anon, demo, moderator }) => {
  const data = { key: `conformance.denied.${uid()}`, language: "en", text: "nope" };
  await expectProblem(await anon.post("/api/v1/messages", { data }), 401);
  await expectProblem(await demo.post("/api/v1/messages", { data }), 403);
  await expectProblem(await moderator.post("/api/v1/messages", { data }), 403);
});

test("create, update and delete round-trip; duplicates conflict", async ({ admin, anon }) => {
  const key = `conformance.crud.${uid()}`;
  const created = await createMessage(admin, { key, language: "en", text: "v1 text" });
  expect(created.key).toBe(key);

  // (key, language) is unique — a second create conflicts
  await expectProblem(
    await admin.post("/api/v1/messages", { data: { key, language: "en", text: "again" } }),
    409,
  );

  const updated = await admin.put(`/api/v1/messages/${created.id}`, {
    data: { key, language: "en", text: "v2 text" },
  });
  expect(updated.status(), await updated.text()).toBe(200);
  expect(((await updated.json()) as Message).text).toBe("v2 text");

  expect((await admin.delete(`/api/v1/messages/${created.id}`)).status()).toBe(204);
  await expectProblem(await anon.get(`/api/v1/messages/${created.id}`), 404);
});

test("any message write changes the ETags of affected reads", async ({ anon, admin }) => {
  const before = await anon.get("/api/v1/messages/bundle?lang=en");
  const staleEtag = before.headers()["etag"] ?? "";

  const created = await createMessage(admin, {
    key: `conformance.etag.${uid()}`,
    language: "en",
    text: "cache buster",
  });

  const after = await anon.get("/api/v1/messages/bundle?lang=en", {
    headers: { "If-None-Match": staleEtag },
  });
  expect(after.status(), "a write must invalidate the previous ETag").toBe(200);
  expect(after.headers()["etag"]).not.toBe(staleEtag);

  await admin.delete(`/api/v1/messages/${created.id}`);
});

test("message input is validated", async ({ admin }) => {
  await expectProblem(
    await admin.post("/api/v1/messages", { data: { key: "Not.Lower", language: "en", text: "x" } }),
    400,
  );
  await expectProblem(
    await admin.post("/api/v1/messages", { data: { key: `conformance.lang.${uid()}`, language: "english", text: "x" } }),
    400,
  );
  await expectProblem(
    await admin.post("/api/v1/messages", { data: { key: `conformance.text.${uid()}`, language: "en", text: "" } }),
    400,
  );
});

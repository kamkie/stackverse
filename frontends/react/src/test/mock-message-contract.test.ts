import { describe, expect, it } from "vitest";
import type { components } from "../api/schema";
import { db } from "../mocks/db";
import { MOCK_USERS, setCurrentUser } from "../mocks/state";
import { apiRequest, responseJson } from "./http";

type Message = components["schemas"]["Message"];
type MessageBundle = components["schemas"]["MessageBundle"];
type MessagePage = components["schemas"]["MessagePage"];
type Problem = components["schemas"]["Problem"];

describe("message mock contract", () => {
  it("resolves language quality, falls back per key, and revalidates bundles", async () => {
    const english = db.messages.find((message) => message.language === "en");
    if (!english) throw new Error("message seed data is incomplete");
    const polishIndex = db.messages.findIndex(
      (message) => message.key === english.key && message.language === "pl",
    );
    if (polishIndex === -1) throw new Error("bilingual message seed data is incomplete");
    db.messages.splice(polishIndex, 1);

    const bundleResponse = await apiRequest("/api/v1/messages/bundle?lang=zz", {
      headers: { "Accept-Language": "en;q=0.2, pl;q=0.9, de;q=bogus" },
    });
    expect(bundleResponse.status).toBe(200);
    expect(bundleResponse.headers.get("Content-Language")).toBe("pl");
    expect(bundleResponse.headers.get("Cache-Control")).toBe("no-cache");
    const etag = bundleResponse.headers.get("ETag");
    expect(etag).toBeTruthy();
    const bundle = await responseJson<MessageBundle>(bundleResponse);
    expect(bundle.language).toBe("pl");
    expect(bundle.messages[english.key]).toBe(english.text);

    const notModified = await apiRequest("/api/v1/messages/bundle?lang=zz", {
      headers: {
        "Accept-Language": "pl",
        "If-None-Match": etag ?? "",
      },
    });
    expect(notModified.status).toBe(304);
    expect(notModified.headers.get("Content-Language")).toBe("pl");
  });

  it("filters and revalidates public message list and individual reads", async () => {
    const target = db.messages.find(
      (message) => message.language === "en" && message.key.includes("blocked"),
    );
    if (!target) throw new Error("message seed data is incomplete");

    const listResponse = await apiRequest(
      `/api/v1/messages?key=${encodeURIComponent(target.key)}&q=${encodeURIComponent(
        target.text.slice(0, 8),
      )}&language=en&page=0&size=1`,
    );
    const page = await responseJson<MessagePage>(listResponse);
    expect(page.items).toEqual([target]);
    expect(page).toMatchObject({ page: 0, size: 1, totalItems: 1, totalPages: 1 });
    const listEtag = listResponse.headers.get("ETag");
    expect(
      (
        await apiRequest("/api/v1/messages", {
          headers: { "If-None-Match": listEtag ?? "" },
        })
      ).status,
    ).toBe(304);

    const itemResponse = await apiRequest(`/api/v1/messages/${target.id}`);
    expect(await responseJson<Message>(itemResponse)).toEqual(target);
    const itemEtag = itemResponse.headers.get("ETag");
    expect(
      (
        await apiRequest(`/api/v1/messages/${target.id}`, {
          headers: { "If-None-Match": itemEtag ?? "" },
        })
      ).status,
    ).toBe(304);
    expect(
      (
        await apiRequest(
          "/api/v1/messages/00000000-0000-4000-8000-999999999999",
        )
      ).status,
    ).toBe(404);
  });

  it("validates and audits admin message creation and conflict handling", async () => {
    const validBody = {
      key: "ui.test.contract-message",
      language: "en",
      text: "Contract message",
      description: "Created by the contract test",
    };
    expect(
      (
        await apiRequest("/api/v1/messages", {
          method: "POST",
          body: validBody,
        })
      ).status,
    ).toBe(401);
    setCurrentUser(MOCK_USERS.demo);
    expect(
      (
        await apiRequest("/api/v1/messages", {
          method: "POST",
          body: validBody,
        })
      ).status,
    ).toBe(403);

    setCurrentUser(MOCK_USERS.admin);
    const invalid = await apiRequest("/api/v1/messages", {
      method: "POST",
      headers: { "Accept-Language": "pl" },
      body: { key: "INVALID KEY", language: "english", text: "   " },
    });
    expect(invalid.status).toBe(400);
    expect((await responseJson<Problem>(invalid)).errors?.map((error) => error.field)).toEqual([
      "key",
      "language",
      "text",
    ]);

    const existing = db.messages[0];
    if (!existing) throw new Error("message seed data is incomplete");
    expect(
      (
        await apiRequest("/api/v1/messages", {
          method: "POST",
          body: {
            key: existing.key,
            language: existing.language,
            text: "Duplicate",
          },
        })
      ).status,
    ).toBe(409);

    const previousVersion = db.messagesVersion;
    const createdResponse = await apiRequest("/api/v1/messages", {
      method: "POST",
      body: validBody,
    });
    expect(createdResponse.status).toBe(201);
    const created = await responseJson<Message>(createdResponse);
    expect(created).toMatchObject(validBody);
    expect(createdResponse.headers.get("Location")).toBe(`/api/v1/messages/${created.id}`);
    expect(db.messagesVersion).toBe(previousVersion + 1);
    expect(db.audit[0]).toMatchObject({
      actor: "admin",
      action: "message.created",
      targetId: created.id,
      detail: { key: validBody.key },
    });
  });

  it("enforces update/delete boundaries and changes ETags after successful writes", async () => {
    setCurrentUser(MOCK_USERS.admin);
    const target = db.messages.find((message) => message.description !== undefined) ?? db.messages[0];
    const duplicate = db.messages.find(
      (message) => target && message.id !== target.id && message.language === target.language,
    );
    if (!target || !duplicate) throw new Error("message seed data is incomplete");

    expect(
      (
        await apiRequest(
          "/api/v1/messages/00000000-0000-4000-8000-999999999999",
          {
            method: "PUT",
            body: { key: "ui.missing", language: "en", text: "Missing" },
          },
        )
      ).status,
    ).toBe(404);
    expect(
      (
        await apiRequest(`/api/v1/messages/${target.id}`, {
          method: "PUT",
          body: {
            key: duplicate.key,
            language: duplicate.language,
            text: "Duplicate",
          },
        })
      ).status,
    ).toBe(409);

    const oldBundle = await apiRequest("/api/v1/messages/bundle?lang=en");
    const oldEtag = oldBundle.headers.get("ETag");
    const updatedResponse = await apiRequest(`/api/v1/messages/${target.id}`, {
      method: "PUT",
      body: {
        key: "ui.test.updated-message",
        language: "pl",
        text: "Zaktualizowano",
      },
    });
    expect(updatedResponse.status).toBe(200);
    const updated = await responseJson<Message>(updatedResponse);
    expect(updated).toMatchObject({
      key: "ui.test.updated-message",
      language: "pl",
      text: "Zaktualizowano",
    });
    expect(updated.description).toBeUndefined();
    expect(db.audit[0]).toMatchObject({ action: "message.updated", targetId: target.id });
    expect(
      (
        await apiRequest("/api/v1/messages/bundle?lang=en", {
          headers: { "If-None-Match": oldEtag ?? "" },
        })
      ).status,
    ).toBe(200);

    setCurrentUser(null);
    expect(
      (
        await apiRequest(`/api/v1/messages/${target.id}`, {
          method: "DELETE",
        })
      ).status,
    ).toBe(401);
    setCurrentUser(MOCK_USERS.demo);
    expect(
      (
        await apiRequest(`/api/v1/messages/${target.id}`, {
          method: "DELETE",
        })
      ).status,
    ).toBe(403);
    setCurrentUser(MOCK_USERS.admin);
    expect(
      (
        await apiRequest(
          "/api/v1/messages/00000000-0000-4000-8000-999999999999",
          { method: "DELETE" },
        )
      ).status,
    ).toBe(404);
    expect(
      (
        await apiRequest(`/api/v1/messages/${target.id}`, {
          method: "DELETE",
        })
      ).status,
    ).toBe(204);
    expect(db.messages.some((message) => message.id === target.id)).toBe(false);
    expect(db.audit[0]).toMatchObject({ action: "message.deleted", targetId: target.id });
  });
});

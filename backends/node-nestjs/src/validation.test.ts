import type { ArgumentMetadata } from "@nestjs/common";
import { describe, expect, it } from "vitest";
import { UserStatusBodyDto } from "./admin-users/user-status.dto.js";
import { BookmarkBodyDto } from "./bookmarks/bookmark.dto.js";
import { MessageBodyDto } from "./messages/message.dto.js";
import { BookmarkStatusBodyDto, ReportBodyDto, ResolutionBodyDto } from "./moderation/moderation.dto.js";
import { ValidationProblem } from "./problems.js";
import { contractValidationPipe } from "./validation.pipe.js";

const metadata = (metatype: ArgumentMetadata["metatype"]): ArgumentMetadata => ({
  type: "body",
  metatype,
});

async function transform<T extends object>(metatype: new () => T, body: unknown): Promise<T> {
  return contractValidationPipe().transform(body, metadata(metatype)) as Promise<T>;
}

async function violations<T extends object>(metatype: new () => T, body: unknown) {
  try {
    await transform(metatype, body);
    return [];
  } catch (error) {
    if (error instanceof ValidationProblem) return error.violations;
    throw error;
  }
}

describe("DTO validation metadata", () => {
  it("normalizes bookmark inputs and applies defaults", async () => {
    const input = await transform(BookmarkBodyDto, {
      url: " https://example.com ",
      title: " t ",
      tags: [" Node ", "node", "web"],
    });

    expect(input).toMatchObject({
      url: "https://example.com",
      title: "t",
      notes: null,
      tags: ["node", "web"],
      visibility: "private",
    });
  });

  it("maps malformed bookmark values to canonical keys without coercion", async () => {
    await expect(violations(BookmarkBodyDto, { title: "t" })).resolves.toContainEqual({
      field: "url",
      messageKey: "validation.url.required",
    });
    await expect(
      violations(BookmarkBodyDto, { url: "https://example.com", title: "t", tags: [42] }),
    ).resolves.toContainEqual({ field: "tags", messageKey: "validation.tag.invalid" });
    await expect(
      violations(BookmarkBodyDto, { url: "https://example.com", title: "t", notes: 42 }),
    ).resolves.toContainEqual({ field: "notes", messageKey: "validation.notes.too-long" });
  });

  it.each([
    [
      MessageBodyDto,
      { key: "example", language: "en", text: "x", description: 42 },
      "description",
      "validation.message.description.too-long",
    ],
    [ReportBodyDto, { reason: "spam", comment: 42 }, "comment", "validation.report.comment.too-long"],
    [ResolutionBodyDto, { resolution: "dismissed", note: 42 }, "note", "validation.resolution.note.too-long"],
    [BookmarkStatusBodyDto, { status: "active", note: 42 }, "note", "validation.bookmark-status.note.too-long"],
    [UserStatusBodyDto, { status: "active", reason: 42 }, "reason", "validation.block.reason.too-long"],
  ] as const)("rejects wrong optional scalar types for %s", async (metatype, body, field, messageKey) => {
    await expect(violations(metatype, body)).resolves.toContainEqual({ field, messageKey });
  });
});

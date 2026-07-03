import { BadRequestProblem } from "./problems.js";

/**
 * Keyset position for the v2 listing: the `(createdAt, id)` of the last item on
 * the previous page, wrapped in base64url so clients treat it as opaque. Keyset
 * pagination is what makes v2 stable under concurrent inserts — new rows land
 * before the cursor position and cannot shift what the next page returns.
 */
export interface BookmarkCursor {
  createdAt: Date;
  id: string;
}

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

export function encodeCursor(cursor: BookmarkCursor): string {
  return Buffer.from(`${cursor.createdAt.toISOString()}|${cursor.id}`, "utf8").toString("base64url");
}

export function decodeCursor(value: string): BookmarkCursor {
  const decoded = Buffer.from(value, "base64url").toString("utf8");
  const separator = decoded.indexOf("|");
  const createdAt = new Date(decoded.slice(0, separator));
  const id = decoded.slice(separator + 1);
  if (separator < 0 || Number.isNaN(createdAt.getTime()) || !UUID_PATTERN.test(id)) {
    throw new BadRequestProblem("The cursor is malformed or unresolvable.");
  }
  return { createdAt, id };
}

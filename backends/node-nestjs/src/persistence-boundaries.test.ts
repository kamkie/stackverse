import { beforeEach, describe, expect, it, vi } from "vitest";

const { readdirMock, readFileMock, poolQueryMock, logEventMock } = vi.hoisted(() => ({
  readdirMock: vi.fn(),
  readFileMock: vi.fn(),
  poolQueryMock: vi.fn(),
  logEventMock: vi.fn(),
}));

vi.mock("node:fs/promises", () => ({
  readdir: readdirMock,
  readFile: readFileMock,
}));
vi.mock("./config.js", () => ({ config: { seedMessagesDir: "C:\\stackverse-seed" } }));
vi.mock("./db.js", () => ({ pool: { query: poolQueryMock } }));
vi.mock("./logging.js", () => ({ logEvent: logEventMock }));

import { recordAudit } from "./audit.js";
import { seedMessages } from "./seed.js";

beforeEach(() => {
  readdirMock.mockReset();
  readFileMock.mockReset();
  poolQueryMock.mockReset();
  logEventMock.mockReset();
});

describe("append-only audit persistence", () => {
  it("writes structured detail through the supplied transaction client", async () => {
    const query = vi.fn().mockResolvedValue({ rows: [], rowCount: 1 });

    await recordAudit({ query } as never, "mod", "bookmark.status-changed", "bookmark", "bookmark-1", {
      from: "active",
      to: "hidden",
    });

    const [sql, values] = query.mock.calls[0] as [string, unknown[]];
    expect(sql).toContain("insert into audit_entries");
    expect(sql).not.toMatch(/update|delete/i);
    expect(values).toEqual([
      expect.stringMatching(/^[0-9a-f-]{36}$/),
      "mod",
      "bookmark.status-changed",
      "bookmark",
      "bookmark-1",
      JSON.stringify({ from: "active", to: "hidden" }),
      expect.any(Date),
    ]);
  });

  it("stores absent detail as SQL null", async () => {
    const query = vi.fn().mockResolvedValue({ rows: [], rowCount: 1 });

    await recordAudit({ query } as never, "admin", "user.unblocked", "user", "demo");

    expect(query.mock.calls[0]?.[1]?.[5]).toBeNull();
  });
});

describe("idempotent runtime message seeding", () => {
  it("sorts language files, inserts only absent pairs, and reports inserted versus skipped", async () => {
    readdirMock.mockResolvedValueOnce(["pl.json", "README.txt", "en.json"]);
    readFileMock.mockImplementation(async (file: string) =>
      file.endsWith("en.json")
        ? JSON.stringify({ "ui.cancel": "Cancel", "ui.save": "Save" })
        : JSON.stringify({ "ui.cancel": "Anuluj" }),
    );
    poolQueryMock.mockResolvedValueOnce({ rows: [], rowCount: 1 }).mockResolvedValueOnce({ rows: [], rowCount: 0 });

    await seedMessages();

    expect(readFileMock.mock.calls.map((call) => call[0] as string)).toEqual([
      expect.stringMatching(/en\.json$/),
      expect.stringMatching(/pl\.json$/),
    ]);
    expect(poolQueryMock.mock.calls[0]?.[1]).toEqual(["en", ["ui.cancel", "ui.save"], ["Cancel", "Save"]]);
    expect(poolQueryMock.mock.calls[1]?.[1]).toEqual(["pl", ["ui.cancel"], ["Anuluj"]]);
    expect(logEventMock.mock.calls).toEqual([
      [
        "info",
        "message_seed_imported",
        "success",
        "Message seed 'en': 1 inserted, 1 already present",
        { language: "en", inserted: 1, skipped: 1 },
      ],
      [
        "info",
        "message_seed_imported",
        "success",
        "Message seed 'pl': 0 inserted, 1 already present",
        { language: "pl", inserted: 0, skipped: 1 },
      ],
    ]);
  });

  it("fails clearly when the configured seed directory is missing", async () => {
    readdirMock.mockRejectedValueOnce(new Error("missing"));

    await expect(seedMessages()).rejects.toThrow(
      "Message seed directory not found: C:\\stackverse-seed — set SEED_MESSAGES_DIR to the spec/messages directory",
    );
    expect(poolQueryMock).not.toHaveBeenCalled();
  });
});

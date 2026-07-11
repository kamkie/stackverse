import { beforeEach, describe, expect, it, vi } from "vitest";

const { connectMock, migrationRunnerMock, logEventMock, loggerDebugMock, poolState } = vi.hoisted(() => ({
  connectMock: vi.fn(),
  migrationRunnerMock: vi.fn(),
  logEventMock: vi.fn(),
  loggerDebugMock: vi.fn(),
  poolState: { errorHandler: undefined as ((error: Error) => void) | undefined },
}));

vi.mock("pg", () => ({
  default: {
    Pool: class Pool {
      connect = connectMock;

      on(event: string, handler: (error: Error) => void) {
        if (event === "error") poolState.errorHandler = handler;
        return this;
      }
    },
  },
}));
vi.mock("node-pg-migrate", () => ({ runner: migrationRunnerMock }));
vi.mock("./config.js", () => ({
  config: { db: { host: "localhost", port: 5432, database: "test", user: "test", password: "test" } },
}));
vi.mock("./logging.js", () => ({
  logEvent: logEventMock,
  logger: { debug: loggerDebugMock },
}));

import { runMigrations, withTransaction } from "./db.js";

beforeEach(() => {
  connectMock.mockReset();
  migrationRunnerMock.mockReset();
  logEventMock.mockReset();
  loggerDebugMock.mockReset();
});

describe("database transaction boundary", () => {
  it("commits successful work and always releases the client", async () => {
    const query = vi.fn().mockResolvedValue({ rows: [] });
    const release = vi.fn();
    connectMock.mockResolvedValueOnce({ query, release });

    const result = await withTransaction(async (client) => {
      await client.query("update bookmarks set title = $1", ["Changed"]);
      return "done";
    });

    expect(result).toBe("done");
    expect(query.mock.calls).toEqual([["begin"], ["update bookmarks set title = $1", ["Changed"]], ["commit"]]);
    expect(release).toHaveBeenCalledOnce();
  });

  it("rolls back failures, preserves the original error, and releases even if rollback fails", async () => {
    const original = new Error("business failure");
    const query = vi.fn(async (sql: string) => {
      if (sql === "rollback") throw new Error("connection already gone");
      return { rows: [] };
    });
    const release = vi.fn();
    connectMock.mockResolvedValueOnce({ query, release });

    await expect(
      withTransaction(async () => {
        throw original;
      }),
    ).rejects.toBe(original);

    expect(query.mock.calls).toEqual([["begin"], ["rollback"]]);
    expect(release).toHaveBeenCalledOnce();
  });
});

describe("database lifecycle", () => {
  it("logs pool connection failures as dependency events", () => {
    const error = Object.assign(new Error("socket reset"), { code: "ECONNRESET" });

    poolState.errorHandler?.(error);

    expect(logEventMock).toHaveBeenCalledWith(
      "error",
      "dependency_call_failed",
      "failure",
      "PostgreSQL connection failed",
      { dependency: "postgres", error_code: "ECONNRESET" },
    );
  });

  it("runs upward migrations and emits one lifecycle event per applied migration", async () => {
    migrationRunnerMock.mockResolvedValueOnce([{ name: "0001_schema" }, { name: "0002_indexes" }]);

    await runMigrations();

    expect(migrationRunnerMock).toHaveBeenCalledWith(
      expect.objectContaining({
        databaseUrl: { host: "localhost", port: 5432, database: "test", user: "test", password: "test" },
        direction: "up",
        migrationsTable: "pgmigrations",
        dir: expect.stringContaining("migrations"),
        log: expect.any(Function),
      }),
    );
    expect(logEventMock.mock.calls).toEqual([
      ["info", "db_migration_applied", "success", "Applied migration 0001_schema", { migration: "0001_schema" }],
      ["info", "db_migration_applied", "success", "Applied migration 0002_indexes", { migration: "0002_indexes" }],
    ]);
  });
});

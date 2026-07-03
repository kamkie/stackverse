import path from "node:path";
import { fileURLToPath } from "node:url";
import pg from "pg";
import { runner as runMigrationRunner } from "node-pg-migrate";
import { config } from "./config.js";
import { logEvent, logger } from "./logging.js";

export const pool = new pg.Pool({ ...config.db, max: 10 });

pool.on("error", (error) => {
  logEvent("error", "dependency_call_failed", "failure", "PostgreSQL connection failed", {
    dependency: "postgres",
    error_code: (error as { code?: string }).code ?? "connection_error",
  });
});

/** Anything that can run a query — the pool, or a client inside a transaction. */
export interface Queryable {
  query(text: string, values?: unknown[]): Promise<pg.QueryResult>;
}

export async function withTransaction<T>(fn: (client: pg.PoolClient) => Promise<T>): Promise<T> {
  const client = await pool.connect();
  try {
    await client.query("begin");
    const result = await fn(client);
    await client.query("commit");
    return result;
  } catch (error) {
    await client.query("rollback").catch(() => {});
    throw error;
  } finally {
    client.release();
  }
}

/** Schema migrations run automatically on startup (SPEC acceptance checklist). */
export async function runMigrations(): Promise<void> {
  const migrationsDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../migrations");
  const applied = await runMigrationRunner({
    databaseUrl: config.db,
    dir: migrationsDir,
    direction: "up",
    migrationsTable: "pgmigrations",
    log: (message: string) => logger.debug(message),
  });
  for (const migration of applied) {
    logEvent("info", "db_migration_applied", "success", `Applied migration ${migration.name}`, {
      migration: migration.name,
    });
  }
}

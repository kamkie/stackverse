import { readdir, readFile } from "node:fs/promises";
import path from "node:path";
import { config } from "./config.js";
import { pool } from "./db.js";
import { logEvent } from "./logging.js";

/**
 * SPEC rule 12: import the JSON seed files from `spec/messages` (language =
 * filename), inserting only `(key, language)` pairs that don't exist yet —
 * runtime edits by admins survive restarts. Seed inserts are not moderator
 * actions, so they are deliberately not audited.
 */
export async function seedMessages(): Promise<void> {
  const dir = config.seedMessagesDir;
  const files = (await readdir(dir).catch(() => null))?.filter((file) => file.endsWith(".json")).sort();
  if (!files) {
    throw new Error(
      `Message seed directory not found: ${dir} — set SEED_MESSAGES_DIR to the spec/messages directory`,
    );
  }
  for (const file of files) {
    const language = path.basename(file, ".json");
    const entries = JSON.parse(await readFile(path.join(dir, file), "utf8")) as Record<string, string>;
    const keys = Object.keys(entries);
    const result = await pool.query(
      `insert into messages (id, key, language, text, created_at, updated_at)
       select gen_random_uuid(), key, $1, text, now(), now()
       from unnest($2::text[], $3::text[]) as seed(key, text)
       on conflict (key, language) do nothing`,
      [language, keys, keys.map((key) => entries[key])],
    );
    const inserted = result.rowCount ?? 0;
    logEvent(
      "info",
      "message_seed_imported",
      "success",
      `Message seed '${language}': ${inserted} inserted, ${keys.length - inserted} already present`,
      { language, inserted, skipped: keys.length - inserted },
    );
  }
}

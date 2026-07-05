import { pool } from "./db.js";

export const DEFAULT_LANGUAGE = "en";

/**
 * `Accept-Language` codes ordered by quality, primary subtags only
 * (`pl-PL;q=0.8` → `pl`). Unparseable entries are skipped, never an error.
 */
export function parseAcceptLanguage(header: string | undefined): string[] {
  if (!header) return [];
  return header
    .split(",")
    .map((part) => {
      const [tag = "", ...parameters] = part.trim().split(";");
      let quality = 1;
      for (const parameter of parameters) {
        const match = /^\s*q=([0-9.]+)\s*$/.exec(parameter);
        if (match) quality = Number(match[1]);
      }
      const code = tag.trim().toLowerCase().split("-")[0] ?? "";
      return { code, quality: Number.isFinite(quality) ? quality : 0 };
    })
    .filter((entry) => /^[a-z]{1,8}$/.test(entry.code))
    .sort((a, b) => b.quality - a.quality) // stable: listing order breaks quality ties
    .map((entry) => entry.code);
}

const supportedLanguages = async (): Promise<Set<string>> => {
  const result = await pool.query("select distinct language from messages");
  return new Set(result.rows.map((row: { language: string }) => row.language));
};

/**
 * SPEC rule 8: explicit `lang` parameter → first supported language in
 * `Accept-Language` (quality-ordered) → `en`. Unsupported values fall back
 * down the chain, never error. "Supported" means at least one message exists
 * in that language.
 */
export async function resolveLanguage(
  lang: string | undefined,
  acceptLanguage: string | undefined,
): Promise<string> {
  const supported = await supportedLanguages();
  if (lang && supported.has(lang)) return lang;
  for (const code of parseAcceptLanguage(acceptLanguage)) {
    if (supported.has(code)) return code;
  }
  return DEFAULT_LANGUAGE;
}

/**
 * Resolves a message key to localized text (SPEC rule 11): language per rule 8,
 * text from the messages table, `en` fallback, and finally the key itself if
 * no text exists at all.
 */
export async function localize(key: string, language: string): Promise<string> {
  const result = await pool.query(
    `select text from messages where key = $1 and language = any($2::text[])
     order by case when language = $3 then 0 else 1 end limit 1`,
    [key, [...new Set([language, DEFAULT_LANGUAGE])], language],
  );
  return (result.rows[0] as { text: string } | undefined)?.text ?? key;
}

/**
 * Flat key → text map for one language (SPEC rule 9): every key of the resolved
 * language plus `en` keys the language is missing, which fall back to their
 * `en` text. Keys are sorted so the body — and therefore its ETag — is stable.
 */
export async function messageBundle(language: string): Promise<Record<string, string>> {
  const result = await pool.query(
    "select key, language, text from messages where language = any($1::text[]) order by key",
    [[...new Set([language, DEFAULT_LANGUAGE])]],
  );
  const texts: Record<string, string> = {};
  for (const row of result.rows as { key: string; language: string; text: string }[]) {
    if (row.language === language || !(row.key in texts)) {
      texts[row.key] = row.text;
    }
  }
  return texts;
}

import path from "node:path";
import { fileURLToPath } from "node:url";

/** Env-only configuration (backends/README.md): the environment is the configuration. */
const env = (name: string, fallback: string): string => {
  const value = process.env[name]?.trim();
  return value ? value : fallback;
};

const intEnv = (name: string, fallback: number): number => {
  const value = Number(env(name, String(fallback)));
  if (!Number.isInteger(value)) throw new Error(`${name} must be an integer`);
  return value;
};

const here = path.dirname(fileURLToPath(import.meta.url));

export const config = {
  port: intEnv("PORT", 8080),
  db: {
    host: env("DB_HOST", "localhost"),
    port: intEnv("DB_PORT", 5432),
    database: env("DB_NAME", "stackverse"),
    user: env("DB_USER", "stackverse"),
    password: env("DB_PASSWORD", "stackverse"),
  },
  oidc: {
    /** Expected `iss` claim; also the OIDC discovery base when no JWKS URI is given. */
    issuerUri: env("OIDC_ISSUER_URI", "http://localhost:8180/realms/stackverse"),
    /** Where to fetch signing keys when the issuer host is not dialable from the container (compose). */
    jwksUri: process.env["OIDC_JWKS_URI"]?.trim() || undefined,
    audience: "stackverse-api",
  },
  /** Language = filename; the directory lives at the repo root (spec/messages)
   *  and is copied into the container image by the Dockerfile. */
  seedMessagesDir: env("SEED_MESSAGES_DIR", path.resolve(here, "../../../spec/messages")),
  logLevel: env("LOG_LEVEL", "info").toLowerCase(),
  logFormat: env("LOG_FORMAT", "json").toLowerCase(),
  otelEnabled: env("OTEL_SDK_DISABLED", "true").toLowerCase() === "false",
} as const;

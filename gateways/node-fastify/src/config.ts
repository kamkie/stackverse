import { existsSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));

const env = (source: NodeJS.ProcessEnv, name: string, fallback: string): string => {
  const value = source[name]?.trim();
  return value ? value : fallback;
};

const optionalEnv = (source: NodeJS.ProcessEnv, name: string): string | undefined => {
  const value = source[name]?.trim();
  return value ? value : undefined;
};

const intEnv = (source: NodeJS.ProcessEnv, name: string, fallback: number): number => {
  const value = Number(env(source, name, String(fallback)));
  if (!Number.isInteger(value)) throw new Error(`${name} must be an integer`);
  return value;
};

function trimTrailingSlash(value: string): string {
  return value.replace(/\/+$/, "");
}

function defaultSpaRoot(): string {
  const built = path.resolve(here, "static");
  if (existsSync(built)) return built;
  return path.resolve(here, "../src/static");
}

export interface GatewayConfig {
  port: number;
  backendUrl: URL;
  frontendUrl?: URL;
  spaRoot: string;
  redisUrl: string;
  oidcIssuerUri: string;
  oidcInternalIssuerUri?: string;
  oidcClientId: string;
  oidcClientSecret: string;
  publicUrl: URL;
  cookiesSecure: boolean;
  logLevel: string;
  logFormat: string;
  otelEnabled: boolean;
}

export function loadConfig(source: NodeJS.ProcessEnv = process.env): GatewayConfig {
  const publicUrl = new URL(env(source, "PUBLIC_URL", "http://localhost:8000"));
  const config: GatewayConfig = {
    port: intEnv(source, "PORT", 8000),
    backendUrl: new URL(env(source, "BACKEND_URL", "http://localhost:8080")),
    spaRoot: optionalEnv(source, "SPA_ROOT") ?? defaultSpaRoot(),
    redisUrl: env(source, "REDIS_URL", "redis://localhost:6379"),
    oidcIssuerUri: trimTrailingSlash(env(source, "OIDC_ISSUER_URI", "http://localhost:8180/realms/stackverse")),
    oidcClientId: env(source, "OIDC_CLIENT_ID", "stackverse-gateway"),
    oidcClientSecret: env(source, "OIDC_CLIENT_SECRET", "stackverse-secret"),
    publicUrl,
    cookiesSecure: publicUrl.protocol === "https:",
    logLevel: env(source, "LOG_LEVEL", "info").toLowerCase(),
    logFormat: env(source, "LOG_FORMAT", "json").toLowerCase(),
    otelEnabled: env(source, "OTEL_SDK_DISABLED", "true").toLowerCase() === "false",
  };

  const frontendUrl = optionalEnv(source, "FRONTEND_URL");
  if (frontendUrl) config.frontendUrl = new URL(frontendUrl);

  const internalIssuer = optionalEnv(source, "OIDC_INTERNAL_ISSUER_URI");
  if (internalIssuer) config.oidcInternalIssuerUri = trimTrailingSlash(internalIssuer);

  return config;
}

export const config = loadConfig();

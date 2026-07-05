import { createHash } from "node:crypto";
import { createRemoteJWKSet, jwtVerify, type JWTPayload } from "jose";
import type { GatewayConfig } from "./config.js";
import { logEvent } from "./logging.js";

export interface OidcMetadata {
  authorizationEndpoint: string;
  tokenEndpoint: string;
  jwksUri: string;
  endSessionEndpoint: string;
}

export interface TokenSet {
  accessToken: string;
  refreshToken?: string;
  idToken?: string;
  expiresIn: number;
}

export type FetchLike = typeof fetch;
export type IdTokenVerifier = (idToken: string, nonce: string) => Promise<JWTPayload>;

export class IdpUnavailableError extends Error {
  constructor(message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = "IdpUnavailableError";
  }
}

export class OidcClient {
  private metadataPromise: Promise<OidcMetadata> | undefined;
  private jwks: ReturnType<typeof createRemoteJWKSet> | undefined;

  constructor(
    private readonly config: GatewayConfig,
    private readonly fetchImpl: FetchLike = fetch,
    private readonly verifier?: IdTokenVerifier,
  ) {}

  async authorizationUrl(state: string, codeVerifier: string, nonce: string): Promise<string> {
    const metadata = await this.metadata();
    const url = new URL(metadata.authorizationEndpoint);
    url.searchParams.set("response_type", "code");
    url.searchParams.set("client_id", this.config.oidcClientId);
    url.searchParams.set("redirect_uri", this.redirectUri());
    url.searchParams.set("scope", "openid profile email");
    url.searchParams.set("state", state);
    url.searchParams.set("nonce", nonce);
    url.searchParams.set("code_challenge", pkceChallenge(codeVerifier));
    url.searchParams.set("code_challenge_method", "S256");
    return url.toString();
  }

  async exchangeCode(code: string, codeVerifier: string): Promise<TokenSet> {
    const metadata = await this.metadata();
    const response = await this.fetchImpl(metadata.tokenEndpoint, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "authorization_code",
        code,
        redirect_uri: this.redirectUri(),
        client_id: this.config.oidcClientId,
        client_secret: this.config.oidcClientSecret,
        code_verifier: codeVerifier,
      }),
    });
    if (!response.ok) {
      throw new Error(`token_endpoint_${response.status}`);
    }
    return parseTokenSet(await response.json());
  }

  async verifyIdToken(idToken: string, nonce: string): Promise<JWTPayload> {
    if (this.verifier) return this.verifier(idToken, nonce);

    const metadata = await this.metadata();
    this.jwks ??= createRemoteJWKSet(new URL(metadata.jwksUri));
    const { payload } = await jwtVerify(idToken, this.jwks, {
      issuer: this.config.oidcIssuerUri,
      audience: this.config.oidcClientId,
    });
    if (payload.nonce !== nonce) {
      throw new Error("nonce_mismatch");
    }
    return payload;
  }

  async refresh(refreshToken: string): Promise<TokenSet | null> {
    const started = Date.now();
    let metadata: OidcMetadata;
    try {
      metadata = await this.metadata();
    } catch (error) {
      logEvent("error", "dependency_call_failed", "failure", "Keycloak discovery failed during token refresh; the session is kept", {
        dependency: "keycloak",
        duration_ms: Date.now() - started,
        error_code: error instanceof Error ? error.name : "oidc_discovery_failed",
      });
      throw error instanceof IdpUnavailableError
        ? error
        : new IdpUnavailableError("The IdP could not be discovered to refresh the access token", { cause: error });
    }

    let response: Response;
    try {
      response = await this.fetchImpl(metadata.tokenEndpoint, {
        method: "POST",
        headers: { "content-type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({
          grant_type: "refresh_token",
          refresh_token: refreshToken,
          client_id: this.config.oidcClientId,
          client_secret: this.config.oidcClientSecret,
        }),
      });
    } catch (error) {
      logEvent("error", "dependency_call_failed", "failure", "Keycloak was unreachable during token refresh; the session is kept", {
        dependency: "keycloak",
        duration_ms: Date.now() - started,
        error_code: error instanceof Error ? error.name : "fetch_failed",
      });
      throw new IdpUnavailableError("The IdP could not be reached to refresh the access token", { cause: error });
    }

    if (!response.ok) {
      if (response.status === 400 || response.status === 401) {
        logEvent("warn", "token_refresh_failed", "failure", "Token refresh rejected by the IdP; treating the session as expired", {
          error_code: "idp_rejected",
          idp_status: response.status,
        });
        return null;
      }

      logEvent("error", "dependency_call_failed", "failure", "Keycloak failed during token refresh; the session is kept", {
        dependency: "keycloak",
        duration_ms: Date.now() - started,
        error_code: `idp_status_${response.status}`,
      });
      throw new IdpUnavailableError(`The IdP answered ${response.status} to the token refresh`);
    }

    try {
      return parseTokenSet(await response.json());
    } catch (error) {
      logEvent("error", "dependency_call_failed", "failure", "Keycloak returned an invalid token refresh response; the session is kept", {
        dependency: "keycloak",
        duration_ms: Date.now() - started,
        error_code: error instanceof Error ? error.name : "invalid_token_response",
      });
      throw new IdpUnavailableError("The IdP returned an invalid token refresh response", { cause: error });
    }
  }

  async logout(refreshToken: string): Promise<void> {
    try {
      const metadata = await this.metadata();
      const response = await this.fetchImpl(metadata.endSessionEndpoint, {
        method: "POST",
        headers: { "content-type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({
          client_id: this.config.oidcClientId,
          client_secret: this.config.oidcClientSecret,
          refresh_token: refreshToken,
        }),
      });
      if (!response.ok) {
        logEvent("warn", "idp_logout_failed", "failure", "IdP logout returned a failure; local session destroyed anyway", {
          error_code: "idp_rejected",
          idp_status: response.status,
        });
      }
    } catch {
      logEvent("warn", "idp_logout_failed", "failure", "IdP logout failed; local session destroyed anyway", {
        error_code: "idp_unreachable",
      });
    }
  }

  redirectUri(): string {
    return new URL("/auth/callback", this.config.publicUrl).toString();
  }

  private async metadata(): Promise<OidcMetadata> {
    this.metadataPromise ??= this.fetchMetadata().catch((error: unknown) => {
      this.metadataPromise = undefined;
      throw error;
    });
    return this.metadataPromise;
  }

  private async fetchMetadata(): Promise<OidcMetadata> {
    const browserBase = this.config.oidcIssuerUri;
    const serverBase = this.config.oidcInternalIssuerUri ?? this.config.oidcIssuerUri;
    const discoveryUrl = `${serverBase}/.well-known/openid-configuration`;
    const response = await this.fetchImpl(discoveryUrl);
    if (!response.ok) {
      throw new IdpUnavailableError(`OIDC discovery returned ${response.status}`);
    }
    const document = await response.json() as Record<string, unknown>;
    return {
      authorizationEndpoint: endpoint(document, "authorization_endpoint", "/protocol/openid-connect/auth", browserBase, [
        browserBase,
        serverBase,
      ]),
      tokenEndpoint: endpoint(document, "token_endpoint", "/protocol/openid-connect/token", serverBase, [browserBase, serverBase]),
      jwksUri: endpoint(document, "jwks_uri", "/protocol/openid-connect/certs", serverBase, [browserBase, serverBase]),
      endSessionEndpoint: endpoint(document, "end_session_endpoint", "/protocol/openid-connect/logout", serverBase, [
        browserBase,
        serverBase,
      ]),
    };
  }
}

export function pkceChallenge(verifier: string): string {
  return createHash("sha256").update(verifier).digest("base64url");
}

export function usernameFromIdToken(payload: JWTPayload): string {
  for (const claim of ["preferred_username", "name", "sub"]) {
    const value = payload[claim];
    if (typeof value === "string" && value.length > 0) {
      return value;
    }
  }
  throw new Error("id_token_missing_username");
}

function endpoint(
  document: Record<string, unknown>,
  key: string,
  fallbackPath: string,
  targetBase: string,
  sourceBases: string[],
): string {
  const value = typeof document[key] === "string" ? document[key] : `${targetBase}${fallbackPath}`;
  for (const sourceBase of sourceBases) {
    if (value === sourceBase || value.startsWith(`${sourceBase}/`)) {
      return `${targetBase}${value.slice(sourceBase.length)}`;
    }
  }
  return value;
}

function parseTokenSet(value: unknown): TokenSet {
  if (!value || typeof value !== "object") throw new Error("invalid_token_response");
  const record = value as Record<string, unknown>;
  if (typeof record["access_token"] !== "string" || record["access_token"].length === 0) {
    throw new Error("missing_access_token");
  }
  const tokenSet: TokenSet = {
    accessToken: record["access_token"],
    expiresIn: typeof record["expires_in"] === "number" && Number.isFinite(record["expires_in"])
      ? record["expires_in"]
      : 300,
  };
  if (typeof record["refresh_token"] === "string" && record["refresh_token"].length > 0) {
    tokenSet.refreshToken = record["refresh_token"];
  }
  if (typeof record["id_token"] === "string" && record["id_token"].length > 0) {
    tokenSet.idToken = record["id_token"];
  }
  return tokenSet;
}

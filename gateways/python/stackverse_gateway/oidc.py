from __future__ import annotations

from dataclasses import dataclass
from typing import Any
from urllib.parse import urlencode

import httpx
from authlib.oauth2.rfc7636 import create_s256_code_challenge
from joserfc import jwt
from joserfc.jwk import KeySet
from joserfc.jwt import JWTClaimsRegistry

from .config import GatewayConfig
from .logging import log_event
from .sessions import now_ms


@dataclass(frozen=True)
class OidcMetadata:
    authorization_endpoint: str
    token_endpoint: str
    jwks_uri: str
    end_session_endpoint: str


@dataclass(frozen=True)
class TokenSet:
    access_token: str
    expires_in: int
    refresh_token: str | None = None
    id_token: str | None = None


class IdpUnavailableError(Exception):
    pass


class OidcClient:
    def __init__(self, config: GatewayConfig, http_client: httpx.AsyncClient) -> None:
        self._config = config
        self._http = http_client
        self._metadata: OidcMetadata | None = None
        self._jwks: Any | None = None

    async def authorization_url(self, state: str, code_verifier: str, nonce: str) -> str:
        metadata = await self.metadata()
        params = {
            "response_type": "code",
            "client_id": self._config.oidc_client_id,
            "redirect_uri": self.redirect_uri(),
            "scope": "openid profile email",
            "state": state,
            "nonce": nonce,
            "code_challenge": pkce_challenge(code_verifier),
            "code_challenge_method": "S256",
        }
        return f"{metadata.authorization_endpoint}?{urlencode(params)}"

    async def exchange_code(self, code: str, code_verifier: str) -> TokenSet:
        metadata = await self.metadata()
        response = await self._http.post(
            metadata.token_endpoint,
            data={
                "grant_type": "authorization_code",
                "code": code,
                "redirect_uri": self.redirect_uri(),
                "client_id": self._config.oidc_client_id,
                "client_secret": self._config.oidc_client_secret,
                "code_verifier": code_verifier,
            },
            headers={"content-type": "application/x-www-form-urlencoded"},
        )
        if response.status_code >= 400:
            raise RuntimeError(f"token_endpoint_{response.status_code}")
        return parse_token_set(response.json())

    async def verify_id_token(self, id_token: str, nonce: str) -> dict[str, Any]:
        if self._jwks is None:
            metadata = await self.metadata()
            response = await self._http.get(metadata.jwks_uri)
            if response.status_code >= 400:
                raise IdpUnavailableError(f"JWKS endpoint returned {response.status_code}")
            self._jwks = KeySet.import_key_set(response.json())

        token = jwt.decode(id_token, self._jwks, algorithms=["RS256"])
        claims = dict(token.claims)
        JWTClaimsRegistry(
            iss={"essential": True, "value": self._config.oidc_issuer_uri},
            aud={"essential": True, "values": [self._config.oidc_client_id]},
            nonce={"essential": True, "value": nonce},
        ).validate(claims)
        return claims

    async def refresh(self, refresh_token: str) -> TokenSet | None:
        started = now_ms()
        try:
            metadata = await self.metadata()
        except Exception as exc:
            log_event(
                "error",
                "dependency_call_failed",
                "failure",
                "Keycloak discovery failed during token refresh; the session is kept",
                dependency="keycloak",
                duration_ms=now_ms() - started,
                error_code=type(exc).__name__,
            )
            raise IdpUnavailableError("The IdP could not be discovered to refresh the access token") from exc

        try:
            response = await self._http.post(
                metadata.token_endpoint,
                data={
                    "grant_type": "refresh_token",
                    "refresh_token": refresh_token,
                    "client_id": self._config.oidc_client_id,
                    "client_secret": self._config.oidc_client_secret,
                },
                headers={"content-type": "application/x-www-form-urlencoded"},
            )
        except httpx.HTTPError as exc:
            log_event(
                "error",
                "dependency_call_failed",
                "failure",
                "Keycloak was unreachable during token refresh; the session is kept",
                dependency="keycloak",
                duration_ms=now_ms() - started,
                error_code=type(exc).__name__,
            )
            raise IdpUnavailableError("The IdP could not be reached to refresh the access token") from exc

        if response.status_code >= 400:
            if response.status_code in {400, 401}:
                log_event(
                    "warn",
                    "token_refresh_failed",
                    "failure",
                    "Token refresh rejected by the IdP; treating the session as expired",
                    error_code="idp_rejected",
                    idp_status=response.status_code,
                )
                return None
            log_event(
                "error",
                "dependency_call_failed",
                "failure",
                "Keycloak failed during token refresh; the session is kept",
                dependency="keycloak",
                duration_ms=now_ms() - started,
                error_code=f"idp_status_{response.status_code}",
            )
            raise IdpUnavailableError(f"The IdP answered {response.status_code} to the token refresh")

        try:
            return parse_token_set(response.json())
        except (TypeError, ValueError) as exc:
            log_event(
                "error",
                "dependency_call_failed",
                "failure",
                "Keycloak returned an invalid token refresh response; the session is kept",
                dependency="keycloak",
                duration_ms=now_ms() - started,
                error_code=type(exc).__name__,
            )
            raise IdpUnavailableError("The IdP returned an invalid token refresh response") from exc

    async def logout(self, refresh_token: str) -> None:
        try:
            metadata = await self.metadata()
            response = await self._http.post(
                metadata.end_session_endpoint,
                data={
                    "client_id": self._config.oidc_client_id,
                    "client_secret": self._config.oidc_client_secret,
                    "refresh_token": refresh_token,
                },
                headers={"content-type": "application/x-www-form-urlencoded"},
            )
            if response.status_code >= 400:
                log_event(
                    "warn",
                    "idp_logout_failed",
                    "failure",
                    "IdP logout returned a failure; local session destroyed anyway",
                    error_code="idp_rejected",
                    idp_status=response.status_code,
                )
        except Exception:
            log_event(
                "warn",
                "idp_logout_failed",
                "failure",
                "IdP logout failed; local session destroyed anyway",
                error_code="idp_unreachable",
            )

    def redirect_uri(self) -> str:
        return f"{self._config.public_url}/auth/callback"

    async def metadata(self) -> OidcMetadata:
        if self._metadata is None:
            self._metadata = await self._fetch_metadata()
        return self._metadata

    async def _fetch_metadata(self) -> OidcMetadata:
        browser_base = self._config.oidc_issuer_uri
        server_base = self._config.oidc_internal_issuer_uri or browser_base
        try:
            response = await self._http.get(f"{server_base}/.well-known/openid-configuration")
        except httpx.HTTPError as exc:
            raise IdpUnavailableError("OIDC discovery failed") from exc
        if response.status_code >= 400:
            raise IdpUnavailableError(f"OIDC discovery returned {response.status_code}")

        document = response.json()
        return OidcMetadata(
            authorization_endpoint=_endpoint(
                document,
                "authorization_endpoint",
                "/protocol/openid-connect/auth",
                browser_base,
                (browser_base, server_base),
            ),
            token_endpoint=_endpoint(
                document, "token_endpoint", "/protocol/openid-connect/token", server_base, (browser_base, server_base)
            ),
            jwks_uri=_endpoint(
                document, "jwks_uri", "/protocol/openid-connect/certs", server_base, (browser_base, server_base)
            ),
            end_session_endpoint=_endpoint(
                document,
                "end_session_endpoint",
                "/protocol/openid-connect/logout",
                server_base,
                (browser_base, server_base),
            ),
        )


def pkce_challenge(verifier: str) -> str:
    return create_s256_code_challenge(verifier)


def username_from_id_token(payload: dict[str, Any]) -> str:
    for claim in ("preferred_username", "name", "sub"):
        value = payload.get(claim)
        if isinstance(value, str) and value:
            return value
    raise RuntimeError("id_token_missing_username")


def parse_token_set(value: Any) -> TokenSet:
    if not isinstance(value, dict):
        raise ValueError("invalid_token_response")
    access_token = value.get("access_token")
    if not isinstance(access_token, str) or not access_token:
        raise ValueError("missing_access_token")
    expires_in = value.get("expires_in", 300)
    return TokenSet(
        access_token=access_token,
        expires_in=expires_in if isinstance(expires_in, int) and expires_in > 0 else 300,
        refresh_token=value.get("refresh_token") if isinstance(value.get("refresh_token"), str) else None,
        id_token=value.get("id_token") if isinstance(value.get("id_token"), str) else None,
    )


def _endpoint(
    document: dict[str, Any], key: str, fallback_path: str, target_base: str, source_bases: tuple[str, ...]
) -> str:
    value = document.get(key)
    endpoint = value if isinstance(value, str) else f"{target_base}{fallback_path}"
    for source_base in source_bases:
        if endpoint == source_base or endpoint.startswith(f"{source_base}/"):
            return f"{target_base}{endpoint[len(source_base) :]}"
    return endpoint

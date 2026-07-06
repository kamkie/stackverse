from __future__ import annotations

import json
import secrets
import time
from dataclasses import asdict, dataclass
from typing import Protocol

import redis.asyncio as redis
from redis.exceptions import RedisError

from .logging import log_event

SESSION_PREFIX = "stackverse:session:"
STATE_PREFIX = "stackverse:oidc-state:"


@dataclass
class GatewaySession:
    username: str
    access_token: str | None = None
    refresh_token: str | None = None
    id_token: str | None = None
    expires_at: int = 0
    created_at: int = 0
    updated_at: int = 0


@dataclass
class LoginState:
    code_verifier: str
    nonce: str
    created_at: int


class SessionStore(Protocol):
    async def create_session(self, session: GatewaySession, ttl_seconds: int) -> str: ...
    async def get_session(self, session_id: str) -> GatewaySession | None: ...
    async def save_session(self, session_id: str, session: GatewaySession, ttl_seconds: int) -> None: ...
    async def destroy_session(self, session_id: str) -> None: ...
    async def set_login_state(self, state: str, value: LoginState, ttl_seconds: int) -> None: ...
    async def consume_login_state(self, state: str) -> LoginState | None: ...
    async def close(self) -> None: ...


def now_ms() -> int:
    return int(time.time() * 1000)


def new_session_id() -> str:
    return secrets.token_urlsafe(32)


def _session_key(session_id: str) -> str:
    return f"{SESSION_PREFIX}{session_id}"


def _state_key(state: str) -> str:
    return f"{STATE_PREFIX}{state}"


def _session_from_json(value: str) -> GatewaySession | None:
    try:
        data = json.loads(value)
        return GatewaySession(**data)
    except TypeError, ValueError:
        return None


def _login_state_from_json(value: str) -> LoginState | None:
    try:
        data = json.loads(value)
        return LoginState(**data)
    except TypeError, ValueError:
        return None


class RedisSessionStore:
    def __init__(self, redis_url: str) -> None:
        self._redis = redis.from_url(redis_url, decode_responses=True)

    async def create_session(self, session: GatewaySession, ttl_seconds: int) -> str:
        session_id = new_session_id()
        await self.save_session(session_id, session, ttl_seconds)
        return session_id

    async def get_session(self, session_id: str) -> GatewaySession | None:
        value = await self._redis_call("get_session", self._redis.get(_session_key(session_id)))
        if not value:
            return None
        session = _session_from_json(value)
        if session is None:
            await self.destroy_session(session_id)
        return session

    async def save_session(self, session_id: str, session: GatewaySession, ttl_seconds: int) -> None:
        await self._redis_call(
            "save_session",
            self._redis.set(_session_key(session_id), json.dumps(asdict(session)), ex=ttl_seconds),
        )

    async def destroy_session(self, session_id: str) -> None:
        await self._redis_call("destroy_session", self._redis.delete(_session_key(session_id)))

    async def set_login_state(self, state: str, value: LoginState, ttl_seconds: int) -> None:
        await self._redis_call(
            "set_login_state",
            self._redis.set(_state_key(state), json.dumps(asdict(value)), ex=ttl_seconds),
        )

    async def consume_login_state(self, state: str) -> LoginState | None:
        key = _state_key(state)
        value = await self._redis_call("consume_login_state", self._redis.getdel(key))
        return _login_state_from_json(value) if value else None

    async def close(self) -> None:
        await self._redis.aclose()

    async def _redis_call(self, action: str, operation):  # type: ignore[no-untyped-def]
        try:
            return await operation
        except RedisError as exc:
            log_event(
                "error",
                "dependency_call_failed",
                "failure",
                "Redis session store operation failed",
                dependency="redis",
                error_code=type(exc).__name__,
                action=action,
            )
            raise


@dataclass
class _Expiring[T]:
    value: T
    expires_at: int


class MemorySessionStore:
    def __init__(self) -> None:
        self._sessions: dict[str, _Expiring[GatewaySession]] = {}
        self._states: dict[str, _Expiring[LoginState]] = {}

    async def create_session(self, session: GatewaySession, ttl_seconds: int) -> str:
        session_id = new_session_id()
        await self.save_session(session_id, session, ttl_seconds)
        return session_id

    async def get_session(self, session_id: str) -> GatewaySession | None:
        return self._get_fresh(self._sessions, session_id)

    async def save_session(self, session_id: str, session: GatewaySession, ttl_seconds: int) -> None:
        self._sessions[session_id] = _Expiring(session, now_ms() + ttl_seconds * 1000)

    async def destroy_session(self, session_id: str) -> None:
        self._sessions.pop(session_id, None)

    async def set_login_state(self, state: str, value: LoginState, ttl_seconds: int) -> None:
        self._states[state] = _Expiring(value, now_ms() + ttl_seconds * 1000)

    async def consume_login_state(self, state: str) -> LoginState | None:
        value = self._get_fresh(self._states, state)
        self._states.pop(state, None)
        return value

    async def close(self) -> None:
        return None

    def _get_fresh[T](self, values: dict[str, _Expiring[T]], key: str) -> T | None:
        entry = values.get(key)
        if entry is None:
            return None
        if entry.expires_at <= now_ms():
            values.pop(key, None)
            return None
        return entry.value

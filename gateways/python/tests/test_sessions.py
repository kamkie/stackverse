from __future__ import annotations

from collections.abc import Awaitable

import anyio
import pytest
from redis.exceptions import RedisError

import stackverse_gateway.sessions as sessions
from stackverse_gateway.config import load_config
from stackverse_gateway.logging import configure_logging
from stackverse_gateway.sessions import GatewaySession, LoginState, MemorySessionStore, RedisSessionStore


class FakeRedis:
    def __init__(self) -> None:
        self.values: dict[str, str] = {}
        self.ttls: dict[str, int] = {}
        self.set_calls: list[tuple[str, int]] = []
        self.closed = False
        self.fail_action: str | None = None

    async def get(self, key: str) -> str | None:
        self._maybe_fail("get")
        return self.values.get(key)

    async def set(self, key: str, value: str, *, ex: int) -> None:
        self._maybe_fail("set")
        self.values[key] = value
        self.ttls[key] = ex
        self.set_calls.append((key, ex))

    async def delete(self, key: str) -> None:
        self._maybe_fail("delete")
        self.values.pop(key, None)
        self.ttls.pop(key, None)

    async def getdel(self, key: str) -> str | None:
        self._maybe_fail("getdel")
        self.ttls.pop(key, None)
        return self.values.pop(key, None)

    async def aclose(self) -> None:
        self.closed = True

    def _maybe_fail(self, action: str) -> None:
        if self.fail_action == action:
            raise RedisError(f"{action} failed")


def run[T](awaitable: Awaitable[T]) -> T:
    async def runner() -> T:
        return await awaitable

    return anyio.run(runner)


def make_session() -> GatewaySession:
    return GatewaySession(
        username="alice",
        access_token="access-value",
        refresh_token="refresh-value",
        id_token="id-value",
        expires_at=10_000,
        created_at=1_000,
        updated_at=2_000,
    )


def install_fake_redis(monkeypatch: pytest.MonkeyPatch) -> FakeRedis:
    fake = FakeRedis()
    monkeypatch.setattr(sessions.redis, "from_url", lambda *_args, **_kwargs: fake)
    return fake


def test_redis_store_round_trips_session_with_ttl_and_destroys_it(monkeypatch: pytest.MonkeyPatch) -> None:
    fake = install_fake_redis(monkeypatch)
    monkeypatch.setattr(sessions, "new_session_id", lambda: "opaque-session-id")
    store = RedisSessionStore("redis://redis.test:6379")

    session_id = run(store.create_session(make_session(), 3600))
    loaded = run(store.get_session(session_id))
    run(store.destroy_session(session_id))
    missing = run(store.get_session(session_id))
    run(store.close())

    assert session_id == "opaque-session-id"
    assert loaded == make_session()
    assert fake.set_calls == [("stackverse:session:opaque-session-id", 3600)]
    assert fake.ttls == {}
    assert missing is None
    assert fake.closed is True


def test_redis_login_state_is_ttl_bound_and_atomically_consumed_once(monkeypatch: pytest.MonkeyPatch) -> None:
    fake = install_fake_redis(monkeypatch)
    store = RedisSessionStore("redis://redis.test:6379")
    state = LoginState(code_verifier="pkce-verifier", nonce="nonce-value", created_at=1234)

    run(store.set_login_state("state-value", state, 600))
    first = run(store.consume_login_state("state-value"))
    replay = run(store.consume_login_state("state-value"))

    assert first == state
    assert replay is None
    assert fake.set_calls == [("stackverse:oidc-state:state-value", 600)]
    assert "stackverse:oidc-state:state-value" not in fake.values
    assert "stackverse:oidc-state:state-value" not in fake.ttls


@pytest.mark.parametrize(
    ("key", "value"),
    [
        ("stackverse:session:broken", "not-json"),
        ("stackverse:session:wrong-shape", '{"username":"alice","unexpected":true}'),
    ],
)
def test_redis_store_removes_corrupt_session_payloads(monkeypatch: pytest.MonkeyPatch, key: str, value: str) -> None:
    fake = install_fake_redis(monkeypatch)
    fake.values[key] = value
    store = RedisSessionStore("redis://redis.test:6379")

    loaded = run(store.get_session(key.removeprefix("stackverse:session:")))

    assert loaded is None
    assert key not in fake.values


def test_redis_store_rejects_corrupt_login_state_without_replay(monkeypatch: pytest.MonkeyPatch) -> None:
    fake = install_fake_redis(monkeypatch)
    fake.values["stackverse:oidc-state:broken"] = "[]"
    store = RedisSessionStore("redis://redis.test:6379")

    loaded = run(store.consume_login_state("broken"))

    assert loaded is None
    assert "stackverse:oidc-state:broken" not in fake.values


def test_redis_failure_is_logged_with_safe_dependency_context(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    fake = install_fake_redis(monkeypatch)
    fake.fail_action = "get"
    configure_logging(load_config({"LOG_FORMAT": "json", "OTEL_SDK_DISABLED": "true"}))
    store = RedisSessionStore("redis://user:password@redis.test:6379")

    with pytest.raises(RedisError, match="get failed"):
        run(store.get_session("opaque-secret-session-id"))

    captured = capsys.readouterr().out
    assert '"event":"dependency_call_failed"' in captured
    assert '"dependency":"redis"' in captured
    assert '"action":"get_session"' in captured
    assert "opaque-secret-session-id" not in captured
    assert "password" not in captured


def test_memory_store_expires_sessions_and_consumes_login_state_once(monkeypatch: pytest.MonkeyPatch) -> None:
    clock = 1_000_000
    monkeypatch.setattr(sessions, "now_ms", lambda: clock)
    store = MemorySessionStore()
    state = LoginState(code_verifier="verifier", nonce="nonce", created_at=clock)

    run(store.save_session("session", make_session(), 1))
    run(store.set_login_state("state", state, 1))
    assert run(store.get_session("session")) == make_session()
    assert run(store.consume_login_state("state")) == state
    assert run(store.consume_login_state("state")) is None

    clock += 1_000
    assert run(store.get_session("session")) is None


def test_session_ids_are_high_entropy_and_unique() -> None:
    first = sessions.new_session_id()
    second = sessions.new_session_id()

    assert first != second
    assert len(first) >= 40
    assert len(second) >= 40

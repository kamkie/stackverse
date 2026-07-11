import { beforeEach, describe, expect, it, vi } from "vitest";

interface RedisDouble {
  url: string;
  options: Record<string, unknown>;
  get: ReturnType<typeof vi.fn>;
  set: ReturnType<typeof vi.fn>;
  del: ReturnType<typeof vi.fn>;
  disconnect: ReturnType<typeof vi.fn>;
}

const redisDoubles = vi.hoisted(() => ({ instances: [] as RedisDouble[] }));

vi.mock("ioredis", () => ({
  Redis: class implements RedisDouble {
    readonly get = vi.fn();
    readonly set = vi.fn();
    readonly del = vi.fn();
    readonly disconnect = vi.fn();

    constructor(
      readonly url: string,
      readonly options: Record<string, unknown>,
    ) {
      redisDoubles.instances.push(this);
    }
  },
}));

import { RedisSessionStore, type GatewaySession, type LoginState } from "./session-store.js";

function session(): GatewaySession {
  return {
    username: "demo",
    accessToken: "access-token",
    refreshToken: "refresh-token",
    idToken: "id-token",
    expiresAt: 2_000,
    createdAt: 1_000,
    updatedAt: 1_500,
  };
}

function loginState(): LoginState {
  return { codeVerifier: "verifier", nonce: "nonce", createdAt: 1_000 };
}

beforeEach(() => {
  redisDoubles.instances.length = 0;
});

describe("RedisSessionStore", () => {
  it("stores opaque sessions under the gateway namespace with an explicit TTL", async () => {
    const store = new RedisSessionStore("redis://redis.test:6379/2");
    const redis = redisDoubles.instances[0];
    expect(redis).toBeDefined();
    expect(redis).toMatchObject({
      url: "redis://redis.test:6379/2",
      options: { maxRetriesPerRequest: 2, enableReadyCheck: true },
    });

    const id = await store.createSession(session(), 3_600);

    expect(id).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(redis?.set).toHaveBeenCalledWith(`stackverse:session:${id}`, JSON.stringify(session()), "EX", 3_600);

    redis?.get.mockResolvedValueOnce(JSON.stringify(session()));
    await expect(store.getSession(id)).resolves.toEqual(session());
    expect(redis?.get).toHaveBeenLastCalledWith(`stackverse:session:${id}`);

    await store.destroySession(id);
    expect(redis?.del).toHaveBeenCalledWith(`stackverse:session:${id}`);
  });

  it("removes malformed session JSON instead of returning attacker-controlled state", async () => {
    const store = new RedisSessionStore("redis://redis.test:6379");
    const redis = redisDoubles.instances[0];
    redis?.get.mockResolvedValueOnce("{malformed");

    await expect(store.getSession("corrupt-id")).resolves.toBeNull();
    expect(redis?.del).toHaveBeenCalledWith("stackverse:session:corrupt-id");

    redis?.get.mockResolvedValueOnce(null);
    await expect(store.getSession("missing-id")).resolves.toBeNull();
  });

  it("persists and consumes OIDC login state exactly once, including malformed values", async () => {
    const store = new RedisSessionStore("redis://redis.test:6379");
    const redis = redisDoubles.instances[0];
    const state = loginState();

    await store.setLoginState("state-id", state, 600);
    expect(redis?.set).toHaveBeenCalledWith("stackverse:oidc-state:state-id", JSON.stringify(state), "EX", 600);

    redis?.get.mockResolvedValueOnce(JSON.stringify(state));
    await expect(store.consumeLoginState("state-id")).resolves.toEqual(state);
    expect(redis?.del).toHaveBeenCalledWith("stackverse:oidc-state:state-id");

    redis?.get.mockResolvedValueOnce("not-json");
    await expect(store.consumeLoginState("bad-state")).resolves.toBeNull();
    expect(redis?.del).toHaveBeenCalledWith("stackverse:oidc-state:bad-state");

    redis?.get.mockResolvedValueOnce(null);
    await expect(store.consumeLoginState("missing-state")).resolves.toBeNull();
  });

  it("disconnects its owned Redis client during application shutdown", async () => {
    const store = new RedisSessionStore("redis://redis.test:6379");
    const redis = redisDoubles.instances[0];

    await store.close();

    expect(redis?.disconnect).toHaveBeenCalledOnce();
  });
});

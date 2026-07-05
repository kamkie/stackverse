import { afterEach, describe, expect, it, vi } from "vitest";
import { MemorySessionStore, type GatewaySession, type LoginState } from "./session-store.js";

function session(overrides: Partial<GatewaySession> = {}): GatewaySession {
  const now = Date.now();
  return {
    username: "demo",
    accessToken: "access-token",
    refreshToken: "refresh-token",
    expiresAt: now + 60_000,
    createdAt: now,
    updatedAt: now,
    ...overrides,
  };
}

function loginState(overrides: Partial<LoginState> = {}): LoginState {
  return {
    codeVerifier: "verifier",
    nonce: "nonce",
    createdAt: Date.now(),
    ...overrides,
  };
}

afterEach(() => {
  vi.useRealTimers();
});

describe("MemorySessionStore", () => {
  it("expires sessions according to their TTL", async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-01-01T00:00:00.000Z"));
    const store = new MemorySessionStore();
    await store.saveSession("session-id", session(), 1);

    expect(await store.getSession("session-id")).toMatchObject({ username: "demo" });

    vi.advanceTimersByTime(1_001);
    expect(await store.getSession("session-id")).toBeNull();
  });

  it("consumes login state exactly once and honors its TTL", async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-01-01T00:00:00.000Z"));
    const store = new MemorySessionStore();
    const state = loginState();

    await store.setLoginState("fresh-state", state, 10);
    expect(await store.consumeLoginState("fresh-state")).toEqual(state);
    expect(await store.consumeLoginState("fresh-state")).toBeNull();

    await store.setLoginState("expired-state", state, 1);
    vi.advanceTimersByTime(1_001);
    expect(await store.consumeLoginState("expired-state")).toBeNull();
  });
});

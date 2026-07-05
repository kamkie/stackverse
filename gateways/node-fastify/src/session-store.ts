import { randomBytes } from "node:crypto";
import { Redis } from "ioredis";

export interface GatewaySession {
  username: string;
  accessToken?: string;
  refreshToken?: string;
  idToken?: string;
  expiresAt: number;
  createdAt: number;
  updatedAt: number;
}

export interface LoginState {
  codeVerifier: string;
  nonce: string;
  createdAt: number;
}

export interface SessionStore {
  createSession(session: GatewaySession, ttlSeconds: number): Promise<string>;
  getSession(id: string): Promise<GatewaySession | null>;
  saveSession(id: string, session: GatewaySession, ttlSeconds: number): Promise<void>;
  destroySession(id: string): Promise<void>;
  setLoginState(state: string, value: LoginState, ttlSeconds: number): Promise<void>;
  consumeLoginState(state: string): Promise<LoginState | null>;
  close?(): Promise<void>;
}

const SESSION_PREFIX = "stackverse:session:";
const STATE_PREFIX = "stackverse:oidc-state:";

function newSessionId(): string {
  return randomBytes(32).toString("base64url");
}

function sessionKey(id: string): string {
  return `${SESSION_PREFIX}${id}`;
}

function stateKey(state: string): string {
  return `${STATE_PREFIX}${state}`;
}

export class RedisSessionStore implements SessionStore {
  private readonly redis: Redis;

  constructor(redisUrl: string) {
    this.redis = new Redis(redisUrl, {
      maxRetriesPerRequest: 2,
      enableReadyCheck: true,
    });
  }

  async createSession(session: GatewaySession, ttlSeconds: number): Promise<string> {
    const id = newSessionId();
    await this.saveSession(id, session, ttlSeconds);
    return id;
  }

  async getSession(id: string): Promise<GatewaySession | null> {
    const json = await this.redis.get(sessionKey(id));
    if (!json) return null;
    try {
      return JSON.parse(json) as GatewaySession;
    } catch {
      await this.destroySession(id);
      return null;
    }
  }

  async saveSession(id: string, session: GatewaySession, ttlSeconds: number): Promise<void> {
    await this.redis.set(sessionKey(id), JSON.stringify(session), "EX", ttlSeconds);
  }

  async destroySession(id: string): Promise<void> {
    await this.redis.del(sessionKey(id));
  }

  async setLoginState(state: string, value: LoginState, ttlSeconds: number): Promise<void> {
    await this.redis.set(stateKey(state), JSON.stringify(value), "EX", ttlSeconds);
  }

  async consumeLoginState(state: string): Promise<LoginState | null> {
    const key = stateKey(state);
    const json = await this.redis.get(key);
    if (!json) return null;
    await this.redis.del(key);
    try {
      return JSON.parse(json) as LoginState;
    } catch {
      return null;
    }
  }

  async close(): Promise<void> {
    this.redis.disconnect();
  }
}

interface Expiring<T> {
  value: T;
  expiresAt: number;
}

export class MemorySessionStore implements SessionStore {
  private readonly sessions = new Map<string, Expiring<GatewaySession>>();
  private readonly states = new Map<string, Expiring<LoginState>>();

  async createSession(session: GatewaySession, ttlSeconds: number): Promise<string> {
    const id = newSessionId();
    await this.saveSession(id, session, ttlSeconds);
    return id;
  }

  async getSession(id: string): Promise<GatewaySession | null> {
    return this.getFresh(this.sessions, id);
  }

  async saveSession(id: string, session: GatewaySession, ttlSeconds: number): Promise<void> {
    this.sessions.set(id, { value: session, expiresAt: Date.now() + ttlSeconds * 1000 });
  }

  async destroySession(id: string): Promise<void> {
    this.sessions.delete(id);
  }

  async setLoginState(state: string, value: LoginState, ttlSeconds: number): Promise<void> {
    this.states.set(state, { value, expiresAt: Date.now() + ttlSeconds * 1000 });
  }

  async consumeLoginState(state: string): Promise<LoginState | null> {
    const value = this.getFresh(this.states, state);
    this.states.delete(state);
    return value;
  }

  private getFresh<T>(map: Map<string, Expiring<T>>, key: string): T | null {
    const entry = map.get(key);
    if (!entry) return null;
    if (entry.expiresAt <= Date.now()) {
      map.delete(key);
      return null;
    }
    return entry.value;
  }
}

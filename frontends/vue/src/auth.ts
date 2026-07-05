import { ref } from "vue";
import { api, unwrap } from "./api/client";
import { isUnauthorized } from "./api/problem";
import type { User } from "./types";

export type Session =
  | { authenticated: true; username: string }
  | { authenticated: false };

export const LOGIN_URL = "/auth/login";
export const session = ref<Session | null>(null);
export const me = ref<User | null>(null);

export function isModerator(user: User | null = me.value): boolean {
  return user?.roles.includes("moderator") === true || isAdmin(user);
}

export function isAdmin(user: User | null = me.value): boolean {
  return user?.roles.includes("admin") === true;
}

export async function loadSession(): Promise<void> {
  const response = await fetch(new URL("/auth/session", location.origin));
  session.value = response.ok
    ? ((await response.json()) as Session)
    : { authenticated: false };
  if (session.value.authenticated) {
    await loadMe();
  } else {
    me.value = null;
  }
}

export async function loadMe(): Promise<void> {
  try {
    me.value = unwrap(await api.GET("/api/v1/me"));
  } catch (error) {
    if (isUnauthorized(error)) session.value = { authenticated: false };
    me.value = null;
  }
}

export async function logout(): Promise<void> {
  await fetch(new URL("/auth/logout", location.origin), { method: "POST" });
  session.value = { authenticated: false };
  me.value = null;
}

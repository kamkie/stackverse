import { createSignal } from "solid-js";
import { api } from "./api";
import type { Session, User } from "./types";

export const LOGIN_URL = "/auth/login";

export const [session, setSession] = createSignal<Session | null>(null);
export const [me, setMe] = createSignal<User | null | undefined>(undefined);

export async function refreshSession(): Promise<Session> {
  let next: Session;
  try {
    const response = await fetch(new URL("/auth/session", location.origin));
    next = response.ok ? ((await response.json()) as Session) : { authenticated: false };
  } catch {
    next = { authenticated: false };
  }
  if (next.authenticated) {
    const currentUser = await refreshMe();
    if (!currentUser) next = { authenticated: false };
  } else {
    setMe(null);
  }
  setSession(next);
  return next;
}

export async function refreshMe(): Promise<User | null> {
  try {
    const user = await api<User>("/api/v1/me");
    setMe(user);
    return user;
  } catch {
    setMe(null);
    return null;
  }
}

export async function logout(): Promise<void> {
  await fetch(new URL("/auth/logout", location.origin), { method: "POST" }).catch(
    () => {},
  );
  setSession({ authenticated: false });
  setMe(null);
}

export function expireSession(): void {
  setSession({ authenticated: false });
  setMe(null);
}

export function isModerator(user: User | null | undefined): boolean {
  return Boolean(user?.roles.includes("moderator") || user?.roles.includes("admin"));
}

export function isAdmin(user: User | null | undefined): boolean {
  return Boolean(user?.roles.includes("admin"));
}

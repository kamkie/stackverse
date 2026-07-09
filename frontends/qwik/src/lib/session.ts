import { api } from "./api";
import type { Session, User } from "./types";

export const LOGIN_URL = "/auth/login";

export interface SessionSnapshot {
  session: Session;
  me: User | null;
}

export async function refreshSession(): Promise<SessionSnapshot> {
  let next: Session;
  try {
    const response = await fetch(new URL("/auth/session", location.origin));
    next = response.ok
      ? ((await response.json()) as Session)
      : { authenticated: false };
  } catch {
    next = { authenticated: false };
  }
  if (!next.authenticated) return { session: next, me: null };
  try {
    return { session: next, me: await api<User>("/api/v1/me") };
  } catch {
    return { session: next, me: null };
  }
}

export async function logout(): Promise<void> {
  await fetch(new URL("/auth/logout", location.origin), {
    method: "POST",
  }).catch(() => {});
}

export function isModerator(user: User | null | undefined): boolean {
  return Boolean(
    user?.roles.includes("moderator") || user?.roles.includes("admin"),
  );
}

export function isAdmin(user: User | null | undefined): boolean {
  return Boolean(user?.roles.includes("admin"));
}

import { useQuery } from "@tanstack/react-query";
import { api, unwrap } from "../api/client";
import type { components } from "../api/schema";
import { useSession } from "./session";

export type User = components["schemas"]["User"];

/** Caller identity and roles from `/api/v1/me`; only fetched when a session exists. */
export function useMe() {
  const session = useSession();
  return useQuery({
    queryKey: ["me"],
    queryFn: async () => unwrap(await api.GET("/api/v1/me")),
    enabled: session.data?.authenticated === true,
  });
}

// `admin` is a composite role in Keycloak that includes `moderator`, so an
// admin token carries both strings — but stay defensive about it.
export function isModerator(user: User | undefined): boolean {
  return user?.roles.includes("moderator") === true || isAdmin(user);
}

export function isAdmin(user: User | undefined): boolean {
  return user?.roles.includes("admin") === true;
}

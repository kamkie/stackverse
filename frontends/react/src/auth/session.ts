import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

/** Shape of `GET /auth/session` — the gateway contract (docs/ARCHITECTURE.md). */
export type Session =
  | { authenticated: true; username: string }
  | { authenticated: false };

async function fetchSession(): Promise<Session> {
  const response = await fetch(new URL("/auth/session", location.origin));
  if (!response.ok) return { authenticated: false };
  return (await response.json()) as Session;
}

export function useSession() {
  return useQuery({ queryKey: ["session"], queryFn: fetchSession });
}

/** `POST /auth/logout`, then reset all client state (the session is gone). */
export function useLogout() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async () => {
      await fetch(new URL("/auth/logout", location.origin), {
        method: "POST",
      });
    },
    onSettled: () => queryClient.resetQueries(),
  });
}

/** Login is a full-page redirect into the gateway's OIDC flow — never an XHR. */
export const LOGIN_URL = "/auth/login";

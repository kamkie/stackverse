import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, unwrap } from "../../api/client";
import type { components, operations } from "../../api/schema";

export type Report = components["schemas"]["Report"];
export type ReportStatus = components["schemas"]["ReportStatus"];
export type UserAccount = components["schemas"]["UserAccount"];
export type AuditEntry = components["schemas"]["AuditEntry"];
export type Message = components["schemas"]["Message"];
export type MessageInput = components["schemas"]["MessageInput"];
export type AdminStats = components["schemas"]["AdminStats"];

export function useAdminStats() {
  return useQuery({
    queryKey: ["admin", "stats"],
    queryFn: async () => unwrap(await api.GET("/api/v1/admin/stats")),
  });
}

export function useReports(status: ReportStatus, page: number) {
  return useQuery({
    queryKey: ["admin", "reports", status, page],
    queryFn: async () =>
      unwrap(
        await api.GET("/api/v1/admin/reports", {
          params: { query: { status, page } },
        }),
      ),
  });
}

export function useResolveReport() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({
      id,
      resolution,
      note,
    }: {
      id: string;
      resolution: "dismissed" | "actioned";
      note?: string;
    }) =>
      unwrap(
        await api.PUT("/api/v1/admin/reports/{id}", {
          params: { path: { id } },
          body: { resolution, ...(note ? { note } : {}) },
        }),
      ),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["admin"] });
      void queryClient.invalidateQueries({ queryKey: ["bookmarks"] });
    },
  });
}

type UserQuery = NonNullable<
  operations["listUserAccounts"]["parameters"]["query"]
>;

export function useUserAccounts(query: UserQuery) {
  return useQuery({
    queryKey: ["admin", "users", query],
    queryFn: async () =>
      unwrap(await api.GET("/api/v1/admin/users", { params: { query } })),
  });
}

export function useSetUserStatus() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({
      username,
      status,
      reason,
    }: {
      username: string;
      status: "active" | "blocked";
      reason?: string;
    }) =>
      unwrap(
        await api.PUT("/api/v1/admin/users/{username}/status", {
          params: { path: { username } },
          body: { status, ...(reason !== undefined ? { reason } : {}) },
        }),
      ),
    onSuccess: () =>
      void queryClient.invalidateQueries({ queryKey: ["admin", "users"] }),
  });
}

type AuditQuery = NonNullable<
  operations["listAuditEntries"]["parameters"]["query"]
>;

export function useAuditLog(query: AuditQuery) {
  return useQuery({
    queryKey: ["admin", "audit", query],
    queryFn: async () =>
      unwrap(await api.GET("/api/v1/admin/audit-log", { params: { query } })),
  });
}

type MessagesQuery = NonNullable<operations["listMessages"]["parameters"]["query"]>;

export function useMessages(query: MessagesQuery) {
  return useQuery({
    queryKey: ["admin", "messages", query],
    queryFn: async () =>
      unwrap(await api.GET("/api/v1/messages", { params: { query } })),
  });
}

function useInvalidateMessages() {
  const queryClient = useQueryClient();
  // Message writes change the bundle too, but the bundle refreshes via its own
  // ETag revalidation on the next language (re)load.
  return () => void queryClient.invalidateQueries({ queryKey: ["admin", "messages"] });
}

export function useCreateMessage() {
  const invalidate = useInvalidateMessages();
  return useMutation({
    mutationFn: async (body: MessageInput) =>
      unwrap(await api.POST("/api/v1/messages", { body })),
    onSuccess: invalidate,
  });
}

export function useUpdateMessage() {
  const invalidate = useInvalidateMessages();
  return useMutation({
    mutationFn: async ({ id, body }: { id: string; body: MessageInput }) =>
      unwrap(
        await api.PUT("/api/v1/messages/{id}", { params: { path: { id } }, body }),
      ),
    onSuccess: invalidate,
  });
}

export function useDeleteMessage() {
  const invalidate = useInvalidateMessages();
  return useMutation({
    mutationFn: async (id: string) =>
      unwrap(
        await api.DELETE("/api/v1/messages/{id}", { params: { path: { id } } }),
      ),
    onSuccess: invalidate,
  });
}

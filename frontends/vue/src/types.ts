import type { components } from "./api/schema";

export type Bookmark = components["schemas"]["Bookmark"];
export type BookmarkInput = components["schemas"]["BookmarkInput"];
export type BookmarkCursorPage = components["schemas"]["BookmarkCursorPage"];
export type TagCount = components["schemas"]["TagCount"];
export type Report = components["schemas"]["Report"];
export type ReportInput = components["schemas"]["ReportInput"];
export type ReportPage = components["schemas"]["ReportPage"];
export type ReportStatus = components["schemas"]["ReportStatus"];
export type User = components["schemas"]["User"];
export type UserAccount = components["schemas"]["UserAccount"];
export type UserAccountPage = components["schemas"]["UserAccountPage"];
export type AuditEntry = components["schemas"]["AuditEntry"];
export type AuditPage = components["schemas"]["AuditPage"];
export type AdminStats = components["schemas"]["AdminStats"];
export type Message = components["schemas"]["Message"];
export type MessageInput = components["schemas"]["MessageInput"];
export type MessagePage = components["schemas"]["MessagePage"];
export type MessageBundle = components["schemas"]["MessageBundle"];

export interface PageResult<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

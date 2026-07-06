export type Visibility = "private" | "public";
export type BookmarkStatus = "active" | "hidden";
export type ReportReason = "spam" | "offensive" | "broken-link" | "other";
export type ReportStatus = "open" | "dismissed" | "actioned";
export type UserAccountStatus = "active" | "blocked";

export const REPORT_REASONS = [
  "spam",
  "offensive",
  "broken-link",
  "other",
] as const satisfies readonly ReportReason[];

export const REPORT_STATUSES = [
  "open",
  "dismissed",
  "actioned",
] as const satisfies readonly ReportStatus[];

export interface BookmarkInput {
  url: string;
  title: string;
  notes?: string;
  tags?: string[];
  visibility?: Visibility;
}

export interface Bookmark extends BookmarkInput {
  id: string;
  owner: string;
  status: BookmarkStatus;
  visibility: Visibility;
  tags: string[];
  createdAt: string;
  updatedAt: string;
}

export interface BookmarkCursorPage {
  items: Bookmark[];
  nextCursor?: string;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface TagCount {
  tag: string;
  count: number;
}

export interface ReportInput {
  reason: ReportReason;
  comment?: string;
}

export interface Report extends ReportInput {
  id: string;
  bookmarkId: string;
  reporter: string;
  status: ReportStatus;
  createdAt: string;
  resolvedBy?: string;
  resolvedAt?: string;
  resolutionNote?: string;
}

export interface User {
  username: string;
  name?: string;
  email?: string;
  roles: string[];
}

export interface UserAccount {
  username: string;
  firstSeen: string;
  lastSeen: string;
  status: UserAccountStatus;
  blockedReason?: string;
  bookmarkCount: number;
}

export interface AuditEntry {
  id: string;
  actor: string;
  action: string;
  targetType: string;
  targetId: string;
  detail?: Record<string, unknown>;
  createdAt: string;
}

export interface MessageInput {
  key: string;
  language: string;
  text: string;
  description?: string;
}

export interface Message extends MessageInput {
  id: string;
  createdAt: string;
  updatedAt: string;
}

export interface MessageBundle {
  language: string;
  messages: Record<string, string>;
}

export interface AdminStats {
  totals: {
    users: number;
    bookmarks: number;
    publicBookmarks: number;
    hiddenBookmarks: number;
    openReports: number;
  };
  daily: {
    date: string;
    bookmarksCreated: number;
    activeUsers: number;
  }[];
  topTags: TagCount[];
}

export type Session =
  | { authenticated: true; username: string }
  | { authenticated: false };

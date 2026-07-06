// Hand-written from spec/openapi.yaml — the spec is the truth
// (frontends/README.md). Field comments live in the spec, not here.

export type Visibility = "private" | "public";
export type BookmarkStatus = "active" | "hidden";

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

export interface TagCount {
  tag: string;
  count: number;
}

export interface TagList {
  tags: TagCount[];
}

export type ReportReason = "spam" | "offensive" | "broken-link" | "other";
export type ReportStatus = "open" | "dismissed" | "actioned";

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

export interface ReportResolutionInput {
  resolution: "open" | "dismissed" | "actioned";
  note?: string;
}

export type UserAccountStatus = "active" | "blocked";

export interface UserAccount {
  username: string;
  firstSeen: string;
  lastSeen: string;
  status: UserAccountStatus;
  blockedReason?: string;
  bookmarkCount: number;
}

export interface UserStatusInput {
  status: UserAccountStatus;
  reason?: string;
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
  daily: { date: string; bookmarksCreated: number; activeUsers: number }[];
  topTags: TagCount[];
}

export interface User {
  username: string;
  roles: string[];
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface FieldError {
  field: string;
  messageKey: string;
  message: string;
}

export interface Problem {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  errors?: FieldError[];
}

export type Session =
  | { authenticated: true; username: string }
  | { authenticated: false };

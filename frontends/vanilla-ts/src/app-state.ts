import { RuntimeI18n } from "./i18n";
import type {
  Bookmark,
  BookmarkCursorPage,
  Message,
  Report,
  ReportStatus,
  Session,
  User,
  UserAccount,
} from "./types";

export const i18n = new RuntimeI18n();
export const SUPPORTED_LANGUAGES = ["en", "pl"] as const;
export const THEME_OPTIONS = ["auto", "light", "dark"] as const;
export const REPORTED_STORAGE_KEY = "stackverse.reported";
export const THEME_STORAGE_KEY = "stackverse.theme";

export type ToastVariant = "success" | "danger";

export interface Toast {
  id: number;
  message: string;
  variant: ToastVariant;
}

export interface BookmarkListState {
  q: string;
  tags: string[];
  pages: BookmarkCursorPage[];
  nextCursor?: string;
  generation: number;
  pending?: Promise<void>;
}

export type FormValues = Record<string, string>;

export type DialogState =
  | {
      kind: "bookmark-form";
      mode: "create" | "edit";
      bookmark?: Bookmark;
      values?: FormValues;
      error?: unknown;
    }
  | { kind: "delete-bookmark"; bookmark: Bookmark }
  | {
      kind: "report-bookmark";
      bookmark: Bookmark;
      values?: FormValues;
      error?: unknown;
    }
  | {
      kind: "edit-report";
      report: Report;
      values?: FormValues;
      error?: unknown;
    }
  | { kind: "withdraw-report"; report: Report }
  | {
      kind: "block-user";
      user: UserAccount;
      values?: FormValues;
      error?: unknown;
    }
  | {
      kind: "message-form";
      mode: "create" | "edit";
      message?: Message;
      values?: FormValues;
      error?: unknown;
    }
  | { kind: "delete-message"; message: Message };

function createInitialState() {
  return {
    session: null as Session | null,
    me: null as User | null,
    renderVersion: 0,
    dialog: null as DialogState | null,
    toasts: [] as Toast[],
    nextToastId: 0,
    bookmarks: {
      q: "",
      tags: [],
      pages: [],
      generation: 0,
    } as BookmarkListState,
    feed: { q: "", tags: [], pages: [], generation: 0 } as BookmarkListState,
    myReports: {
      status: "" as ReportStatus | "",
      page: 0,
      items: [] as Report[],
    },
    adminReports: {
      status: "open" as ReportStatus,
      page: 0,
      items: [] as Report[],
    },
    users: { q: "", page: 0, items: [] as UserAccount[] },
    audit: { actor: "", action: "", from: "", to: "", page: 0 },
    messages: { q: "", language: "", page: 0, items: [] as Message[] },
  };
}

export const state = createInitialState();

export function resetAppState(): void {
  Object.assign(state, createInitialState());
}

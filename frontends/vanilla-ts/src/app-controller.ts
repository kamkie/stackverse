import { ApiError, apiGet, apiSend, fetchSession } from "./api";
import { adminPageHtml } from "./admin-pages";
import {
  fetchNextBookmarks,
  findBookmark,
  myBookmarksPageHtml,
  myReportsPageHtml,
  publicFeedPageHtml,
  resetBookmarkList,
} from "./bookmark-pages";
import { dialogHtml } from "./dialog-views";
import { headerHtml } from "./header-view";
import { i18n, state } from "./app-state";
import type { FormValues, ToastVariant } from "./app-state";
import {
  t,
  pathForApi,
  currentPath,
  applyTheme,
  addReportedId,
  removeReportedId,
  toastHtml,
  loadingHtml,
  errorHtml,
} from "./view-helpers";
import type {
  Bookmark,
  BookmarkInput,
  Message,
  MessageInput,
  Report,
  ReportInput,
  ReportReason,
  ReportStatus,
  Session,
  User,
  UserAccount,
  Visibility,
} from "./types";

let root: HTMLElement;

let pendingInputRender: number | undefined;

function pushToast(message: string, variant: ToastVariant = "success"): void {
  const id = state.nextToastId;
  state.nextToastId += 1;
  state.toasts.push({ id, message, variant });
  window.setTimeout(() => {
    state.toasts = state.toasts.filter((toast) => toast.id !== id);
    void renderApp();
  }, 5000);
}

async function loadSessionAndMe(): Promise<void> {
  try {
    state.session = await fetchSession<Session>("/auth/session");
  } catch {
    state.session = { authenticated: false };
  }

  state.me = null;
  const session = state.session;
  if (session.authenticated) {
    try {
      state.me = await apiGet<User>("/api/v1/me");
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        state.session = { authenticated: false };
      }
    }
  }
}

function renderShell(
  mainHtml: string,
  options: { includeDialog?: boolean } = {},
): void {
  const dialogMarkup = options.includeDialog === false ? "" : dialogHtml();
  root.innerHTML = `<div class="sv-app">
    ${headerHtml()}
    <main class="sv-main">${mainHtml}</main>
    ${dialogMarkup}
    ${toastHtml()}
  </div>`;

  const dialog = root.querySelector<HTMLDialogElement>("dialog.sv-dialog");
  if (dialog) {
    if (typeof dialog.showModal === "function" && !dialog.open) {
      dialog.showModal();
    } else {
      dialog.setAttribute("open", "");
    }
    dialog.addEventListener(
      "close",
      () => {
        state.dialog = null;
        void renderApp();
      },
      { once: true },
    );
  }
}

async function renderApp(): Promise<void> {
  if (window.location.pathname === "/") {
    history.replaceState(null, "", "/feed");
  }
  const version = ++state.renderVersion;
  renderShell(loadingHtml(), { includeDialog: false });
  let content: string;
  try {
    content = await routeHtml(currentPath());
  } catch (error) {
    content = errorHtml(error);
  }
  if (version !== state.renderVersion) return;
  renderShell(content);
}

async function routeHtml(path: string): Promise<string> {
  if (path === "/bookmarks") return myBookmarksPageHtml();
  if (path === "/reports") return myReportsPageHtml();
  if (path === "/feed") return publicFeedPageHtml();
  if (path === "/admin" || path.startsWith("/admin/"))
    return adminPageHtml(path);
  return publicFeedPageHtml();
}

function navigate(path: string): void {
  if (path === currentPath()) return;
  history.pushState(null, "", path);
  void renderApp();
}

function scheduleRender(): void {
  if (pendingInputRender !== undefined) window.clearTimeout(pendingInputRender);
  pendingInputRender = window.setTimeout(() => {
    pendingInputRender = undefined;
    void renderApp();
  }, 250);
}

function updateBoundValue(
  bind: string,
  value: string,
  immediate: boolean,
): void {
  switch (bind) {
    case "bookmarks-q":
      state.bookmarks.q = value;
      resetBookmarkList(state.bookmarks);
      break;
    case "feed-q":
      state.feed.q = value;
      resetBookmarkList(state.feed);
      break;
    case "my-reports-status":
      state.myReports.status = value as ReportStatus | "";
      state.myReports.page = 0;
      break;
    case "admin-reports-status":
      state.adminReports.status = value as ReportStatus;
      state.adminReports.page = 0;
      break;
    case "users-q":
      state.users.q = value;
      state.users.page = 0;
      break;
    case "audit-actor":
      state.audit.actor = value;
      state.audit.page = 0;
      break;
    case "audit-action":
      state.audit.action = value;
      state.audit.page = 0;
      break;
    case "audit-from":
      state.audit.from = value;
      state.audit.page = 0;
      break;
    case "audit-to":
      state.audit.to = value;
      state.audit.page = 0;
      break;
    case "messages-q":
      state.messages.q = value;
      state.messages.page = 0;
      break;
    case "messages-language":
      state.messages.language = value;
      state.messages.page = 0;
      break;
  }
  if (immediate) void renderApp();
  else scheduleRender();
}

function formValues(form: HTMLFormElement): FormValues {
  const data = new FormData(form);
  const values: FormValues = {};
  for (const [key, value] of data.entries()) values[key] = String(value);
  return values;
}

type DialogFormErrorUpdate =
  { kind: "clear" } | { kind: "set"; error: unknown };

function rememberDialogFormState(
  form: HTMLFormElement,
  values: FormValues,
  errorUpdate: DialogFormErrorUpdate,
): void {
  if (!state.dialog) return;

  switch (form.dataset.form) {
    case "bookmark":
      if (state.dialog.kind === "bookmark-form") {
        if (errorUpdate.kind === "set") {
          state.dialog = { ...state.dialog, values, error: errorUpdate.error };
        } else {
          delete state.dialog.error;
          state.dialog = { ...state.dialog, values };
        }
      }
      break;
    case "report-bookmark":
      if (state.dialog.kind === "report-bookmark") {
        if (errorUpdate.kind === "set") {
          state.dialog = { ...state.dialog, values, error: errorUpdate.error };
        } else {
          delete state.dialog.error;
          state.dialog = { ...state.dialog, values };
        }
      }
      break;
    case "edit-report":
      if (state.dialog.kind === "edit-report") {
        if (errorUpdate.kind === "set") {
          state.dialog = { ...state.dialog, values, error: errorUpdate.error };
        } else {
          delete state.dialog.error;
          state.dialog = { ...state.dialog, values };
        }
      }
      break;
    case "block-user":
      if (state.dialog.kind === "block-user") {
        if (errorUpdate.kind === "set") {
          state.dialog = { ...state.dialog, values, error: errorUpdate.error };
        } else {
          delete state.dialog.error;
          state.dialog = { ...state.dialog, values };
        }
      }
      break;
    case "message":
      if (state.dialog.kind === "message-form") {
        if (errorUpdate.kind === "set") {
          state.dialog = { ...state.dialog, values, error: errorUpdate.error };
        } else {
          delete state.dialog.error;
          state.dialog = { ...state.dialog, values };
        }
      }
      break;
  }
}

function rememberDialogValues(form: HTMLFormElement): void {
  rememberDialogFormState(form, formValues(form), { kind: "clear" });
}

function reportBody(values: FormValues): ReportInput {
  return {
    reason: (values.reason || "spam") as ReportReason,
    ...(values.comment ? { comment: values.comment } : {}),
  };
}

function bookmarkBody(values: FormValues): BookmarkInput {
  return {
    url: values.url ?? "",
    title: values.title ?? "",
    ...(values.notes ? { notes: values.notes } : {}),
    tags: (values.tags ?? "").split(/[\s,]+/).filter(Boolean),
    visibility: (values.visibility || "private") as Visibility,
  };
}

function messageBody(values: FormValues): MessageInput {
  return {
    key: values.key ?? "",
    language: values.language ?? "en",
    text: values.text ?? "",
    ...(values.description ? { description: values.description } : {}),
  };
}

async function handleForm(form: HTMLFormElement): Promise<void> {
  if (!state.dialog) return;
  const values = formValues(form);
  try {
    switch (form.dataset.form) {
      case "bookmark": {
        if (state.dialog.kind !== "bookmark-form") return;
        if (state.dialog.mode === "edit" && state.dialog.bookmark) {
          await apiSend<Bookmark>(
            "PUT",
            pathForApi("/api/v1/bookmarks", state.dialog.bookmark.id),
            bookmarkBody(values),
          );
        } else {
          await apiSend<Bookmark>(
            "POST",
            "/api/v1/bookmarks",
            bookmarkBody(values),
          );
        }
        resetBookmarkList(state.bookmarks);
        state.dialog = null;

        break;
      }
      case "report-bookmark": {
        if (state.dialog.kind !== "report-bookmark") return;
        try {
          await apiSend<Report>(
            "POST",
            `${pathForApi("/api/v1/bookmarks", state.dialog.bookmark.id)}/reports`,
            reportBody(values),
          );
          pushToast(t("ui.toast.report-submitted"));
        } catch (error) {
          if (error instanceof ApiError && error.status === 409) {
            pushToast(t("ui.toast.report-duplicate"));
          } else {
            throw error;
          }
        }
        addReportedId(state.dialog.bookmark.id);
        state.dialog = null;
        break;
      }
      case "edit-report": {
        if (state.dialog.kind !== "edit-report") return;
        await apiSend<Report>(
          "PUT",
          pathForApi("/api/v1/reports", state.dialog.report.id),
          reportBody(values),
        );
        pushToast(t("ui.toast.report-updated"));
        state.dialog = null;
        break;
      }
      case "block-user": {
        if (state.dialog.kind !== "block-user") return;
        await apiSend<UserAccount>(
          "PUT",
          `${pathForApi("/api/v1/admin/users", state.dialog.user.username)}/status`,
          { status: "blocked", reason: values.reason ?? "" },
        );
        state.dialog = null;
        break;
      }
      case "message": {
        if (state.dialog.kind !== "message-form") return;
        if (state.dialog.mode === "edit" && state.dialog.message) {
          await apiSend<Message>(
            "PUT",
            pathForApi("/api/v1/messages", state.dialog.message.id),
            messageBody(values),
          );
          pushToast(t("ui.toast.message-updated"));
        } else {
          await apiSend<Message>(
            "POST",
            "/api/v1/messages",
            messageBody(values),
          );
          pushToast(t("ui.toast.message-created"));
        }
        await i18n.load();
        state.dialog = null;
        break;
      }
    }
  } catch (error) {
    rememberDialogFormState(form, values, { kind: "set", error });
  }
  await renderApp();
}

async function handleAction(element: HTMLElement): Promise<void> {
  const action = element.dataset.action;
  if (!action || element.hasAttribute("disabled")) return;

  switch (action) {
    case "theme":
      applyTheme(
        (element.dataset.theme ?? "auto") as "auto" | "light" | "dark",
      );
      await renderApp();
      break;
    case "language":
      await i18n.setLanguage(element.dataset.lang ?? "en");
      await renderApp();
      break;
    case "logout":
      await fetch("/auth/logout", { method: "POST", credentials: "include" });
      state.session = { authenticated: false };
      state.me = null;
      state.dialog = null;
      if (currentPath() === "/feed") await renderApp();
      else navigate("/feed");
      break;
    case "close-dialog":
      state.dialog = null;
      await renderApp();
      break;
    case "toggle-tag": {
      const list =
        element.dataset.list === "feed" ? state.feed : state.bookmarks;
      const tag = element.dataset.tag ?? "";
      list.tags = list.tags.includes(tag)
        ? list.tags.filter((existing) => existing !== tag)
        : [...list.tags, tag];
      resetBookmarkList(list);
      await renderApp();
      break;
    }
    case "load-more": {
      if (element.dataset.list === "feed")
        await fetchNextBookmarks(state.feed, "public");
      else await fetchNextBookmarks(state.bookmarks);
      await renderApp();
      break;
    }
    case "page": {
      const page = Math.max(0, Number(element.dataset.page ?? 0));
      switch (element.dataset.bind) {
        case "my-reports":
          state.myReports.page = page;
          break;
        case "admin-reports":
          state.adminReports.page = page;
          break;
        case "users":
          state.users.page = page;
          break;
        case "audit":
          state.audit.page = page;
          break;
        case "messages":
          state.messages.page = page;
          break;
      }
      await renderApp();
      break;
    }
    case "open-bookmark-create":
      state.dialog = { kind: "bookmark-form", mode: "create" };
      await renderApp();
      break;
    case "open-bookmark-edit": {
      const bookmark = findBookmark(element.dataset.id ?? "");
      if (bookmark)
        state.dialog = { kind: "bookmark-form", mode: "edit", bookmark };
      await renderApp();
      break;
    }
    case "open-bookmark-delete": {
      const bookmark = findBookmark(element.dataset.id ?? "");
      if (bookmark) state.dialog = { kind: "delete-bookmark", bookmark };
      await renderApp();
      break;
    }
    case "confirm-bookmark-delete":
      if (state.dialog?.kind === "delete-bookmark") {
        await apiSend<void>(
          "DELETE",
          pathForApi("/api/v1/bookmarks", state.dialog.bookmark.id),
        );
        pushToast(t("ui.toast.bookmark-deleted"));
        resetBookmarkList(state.bookmarks);
        state.dialog = null;
        await renderApp();
      }
      break;
    case "open-report": {
      const bookmark = findBookmark(element.dataset.id ?? "");
      if (bookmark) state.dialog = { kind: "report-bookmark", bookmark };
      await renderApp();
      break;
    }
    case "open-report-edit": {
      const report = state.myReports.items.find(
        (item) => item.id === element.dataset.id,
      );
      if (report) state.dialog = { kind: "edit-report", report };
      await renderApp();
      break;
    }
    case "open-report-withdraw": {
      const report = state.myReports.items.find(
        (item) => item.id === element.dataset.id,
      );
      if (report) state.dialog = { kind: "withdraw-report", report };
      await renderApp();
      break;
    }
    case "confirm-report-withdraw":
      if (state.dialog?.kind === "withdraw-report") {
        await apiSend<void>(
          "DELETE",
          pathForApi("/api/v1/reports", state.dialog.report.id),
        );
        removeReportedId(state.dialog.report.bookmarkId);
        pushToast(t("ui.toast.report-withdrawn"));
        state.dialog = null;
        await renderApp();
      }
      break;
    case "resolve-report":
      await apiSend<Report>(
        "PUT",
        pathForApi("/api/v1/admin/reports", element.dataset.id ?? ""),
        {
          resolution: element.dataset.resolution ?? "dismissed",
        },
      );
      await renderApp();
      break;
    case "open-block-user": {
      const user = state.users.items.find(
        (item) => item.username === element.dataset.username,
      );
      if (user) state.dialog = { kind: "block-user", user };
      await renderApp();
      break;
    }
    case "unblock-user":
      await apiSend<UserAccount>(
        "PUT",
        `${pathForApi("/api/v1/admin/users", element.dataset.username ?? "")}/status`,
        { status: "active" },
      );
      await renderApp();
      break;
    case "clear-audit":
      state.audit.actor = "";
      state.audit.action = "";
      state.audit.from = "";
      state.audit.to = "";
      state.audit.page = 0;
      await renderApp();
      break;
    case "open-message-create":
      state.dialog = { kind: "message-form", mode: "create" };
      await renderApp();
      break;
    case "open-message-edit": {
      const message = state.messages.items.find(
        (item) => item.id === element.dataset.id,
      );
      if (message)
        state.dialog = { kind: "message-form", mode: "edit", message };
      await renderApp();
      break;
    }
    case "open-message-delete": {
      const message = state.messages.items.find(
        (item) => item.id === element.dataset.id,
      );
      if (message) state.dialog = { kind: "delete-message", message };
      await renderApp();
      break;
    }
    case "confirm-message-delete":
      if (state.dialog?.kind === "delete-message") {
        await apiSend<void>(
          "DELETE",
          pathForApi("/api/v1/messages", state.dialog.message.id),
        );
        pushToast(t("ui.toast.message-deleted"));
        await i18n.load();
        state.dialog = null;
        await renderApp();
      }
      break;
    case "clear-messages":
      state.messages.q = "";
      state.messages.language = "";
      state.messages.page = 0;
      await renderApp();
      break;
  }
}

export async function startAppController(
  rootElement: HTMLElement,
  options: { enableDevInstrumentation?: boolean } = {},
): Promise<() => void> {
  root = rootElement;
  const controller = new AbortController();
  const listenerOptions = { signal: controller.signal };

  document.addEventListener(
    "click",
    (event) => {
      const target = event.target instanceof Element ? event.target : null;
      if (!target) return;

      const link = target.closest<HTMLAnchorElement>("a[data-link]");
      if (link) {
        event.preventDefault();
        navigate(new URL(link.href).pathname);
        return;
      }

      const action = target.closest<HTMLElement>("[data-action]");
      if (action) {
        event.preventDefault();
        void handleAction(action);
      }
    },
    listenerOptions,
  );

  document.addEventListener(
    "input",
    (event) => {
      const input = event.target as
        HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement;
      if (input.form?.dataset.form) rememberDialogValues(input.form);
      const bind = input.dataset.bind;
      if (!bind) return;
      const immediate =
        input.tagName === "SELECT" || input.getAttribute("type") === "date";
      updateBoundValue(bind, input.value, immediate);
    },
    listenerOptions,
  );

  document.addEventListener(
    "change",
    (event) => {
      const input = event.target as HTMLInputElement | HTMLSelectElement;
      if (input.form?.dataset.form) rememberDialogValues(input.form);
      const bind = input.dataset.bind;
      if (!bind) return;
      if (input.tagName === "SELECT" || input.getAttribute("type") === "date") {
        updateBoundValue(bind, input.value, true);
      }
    },
    listenerOptions,
  );

  document.addEventListener(
    "submit",
    (event) => {
      const form =
        event.target instanceof HTMLFormElement ? event.target : null;
      if (!form) return;
      event.preventDefault();
      void handleForm(form);
    },
    listenerOptions,
  );

  window.addEventListener(
    "popstate",
    () => {
      void renderApp();
    },
    listenerOptions,
  );

  if (options.enableDevInstrumentation ?? import.meta.env.DEV) {
    const [{ forwardConsoleToDevServer }, { installUserActionLog }] =
      await Promise.all([
        import("./dev/forwardConsoleToDevServer"),
        import("./dev/logUserActions"),
      ]);
    forwardConsoleToDevServer();
    installUserActionLog();
  }
  await i18n.load();
  await loadSessionAndMe();
  await renderApp();

  return () => {
    controller.abort();
    if (pendingInputRender !== undefined) {
      window.clearTimeout(pendingInputRender);
      pendingInputRender = undefined;
    }
  };
}

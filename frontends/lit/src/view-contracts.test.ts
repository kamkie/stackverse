import { ApiError } from "./api";
import { APP_ACTIONS, assertNever, parseAppAction } from "./app-actions";
import { state, THEME_STORAGE_KEY } from "./app-state";
import {
  applyTheme,
  errorHtml,
  isAdmin,
  isModerator,
  paginationHtml,
  pathForApi,
  readReportedIds,
  readStoredTheme,
  removeReportedId,
  addReportedId,
  textFieldHtml,
} from "./view-helpers";

afterEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  document.documentElement.removeAttribute("data-theme");
  history.replaceState(null, "", "/");
  state.toasts = [];
  vi.restoreAllMocks();
});

describe("view contract boundaries", () => {
  it("keeps action parsing fail-closed and makes exhaustive failures visible", () => {
    expect(parseAppAction(APP_ACTIONS.resolveReport)).toBe("resolve-report");
    expect(parseAppAction("resolve-report-and-hide")).toBeUndefined();
    expect(parseAppAction(undefined)).toBeUndefined();
    expect(() => assertNever("future-action" as never)).toThrow(
      "Unhandled app action: future-action",
    );
  });

  it("models moderator and admin authorization hierarchically", () => {
    const regular = { username: "demo", roles: [] };
    const moderator = { username: "mod", roles: ["moderator"] };
    const admin = { username: "admin", roles: ["admin"] };

    expect(isModerator(regular)).toBe(false);
    expect(isModerator(moderator)).toBe(true);
    expect(isModerator(admin)).toBe(true);
    expect(isAdmin(moderator)).toBe(false);
    expect(isAdmin(admin)).toBe(true);
    expect(isAdmin(null)).toBe(false);
  });

  it("keeps theme persistence optional and resets auto mode", () => {
    localStorage.setItem(THEME_STORAGE_KEY, "dark");
    expect(readStoredTheme()).toBe("dark");

    applyTheme("light");
    expect(document.documentElement.dataset.theme).toBe("light");
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe("light");

    applyTheme("auto");
    expect(document.documentElement.hasAttribute("data-theme")).toBe(false);
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBeNull();

    localStorage.setItem(THEME_STORAGE_KEY, "unexpected");
    expect(readStoredTheme()).toBe("auto");
  });

  it("treats malformed or unavailable session storage as an empty report set", () => {
    sessionStorage.setItem("stackverse.reported", "not-json");
    expect(readReportedIds()).toEqual(new Set());

    addReportedId("bookmark/one");
    expect(readReportedIds()).toEqual(new Set(["bookmark/one"]));
    removeReportedId("bookmark/one");
    expect(readReportedIds()).toEqual(new Set());
  });

  it("escapes server validation values and wires accessible field errors", () => {
    const html = textFieldHtml({
      name: "title",
      label: "Title <required>",
      value: 'unsafe" onfocus="alert(1)',
      error: "Must be <safe>",
      hint: "Public value",
    });

    expect(html).toContain("Title &lt;required&gt;");
    expect(html).toContain("unsafe&quot; onfocus=&quot;alert(1)");
    expect(html).toContain(
      'aria-describedby="field-title-hint field-title-error"',
    );
    expect(html).toContain('aria-invalid="true"');
    expect(html).not.toContain("<safe>");
  });

  it("maps authentication errors to login without leaking problem details", () => {
    const unauthorized = errorHtml(
      new ApiError(401, { detail: "token=secret-session-value" }),
    );
    expect(unauthorized).toContain("/auth/login");
    expect(unauthorized).not.toContain("secret-session-value");

    const serverError = errorHtml(new Error("Service unavailable <retry>"));
    expect(serverError).toContain("Service unavailable &lt;retry&gt;");
  });

  it("encodes resource identifiers and clamps pagination controls", () => {
    expect(pathForApi("/api/v1/admin/users", "alice/bob@example.com")).toBe(
      "/api/v1/admin/users/alice%2Fbob%40example.com",
    );
    expect(paginationHtml(0, 1, "users")).toBe("");
    const first = paginationHtml(0, 3, "users");
    expect(first).toContain('data-page="-1" disabled');
    expect(first).toContain('data-page="1"');
    const last = paginationHtml(2, 3, "users");
    expect(last).toContain('data-page="3" disabled');
  });
});

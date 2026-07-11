import { adminPageHtml } from "./admin-pages";
import { i18n, resetAppState, state } from "./app-state";

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function paged<T>(items: T[], totalPages = 1) {
  return {
    items,
    page: 0,
    size: 20,
    totalItems: items.length,
    totalPages,
  };
}

function adminSession(): void {
  state.session = { authenticated: true, username: "admin" };
  state.me = { username: "admin", roles: ["admin", "moderator"] };
}

describe("admin page contract rendering", () => {
  beforeEach(() => {
    resetAppState();
    history.replaceState(null, "", "/admin");
    i18n.lang = "en";
  });

  afterEach(() => {
    resetAppState();
    i18n.lang = null;
    vi.restoreAllMocks();
  });

  it("blocks anonymous and non-moderator callers before privileged API reads", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch");

    state.session = { authenticated: false };
    const anonymous = await adminPageHtml("/admin");
    expect(anonymous.html).toContain('/auth/login');

    state.session = { authenticated: true, username: "demo" };
    state.me = { username: "demo", roles: [] };
    const regular = await adminPageHtml("/admin/reports");
    expect(regular.html).toContain('role="alert">403');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("renders moderator dashboard aggregates without exposing admin-only navigation", async () => {
    state.session = { authenticated: true, username: "moderator" };
    state.me = { username: "moderator", roles: ["moderator"] };
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(
        jsonResponse({
          totals: {
            users: 7,
            bookmarks: 12,
            publicBookmarks: 5,
            hiddenBookmarks: 2,
            openReports: 3,
          },
          daily: [
            { date: "2026-07-10", bookmarksCreated: 2, activeUsers: 4 },
            { date: "2026-07-11", bookmarksCreated: 0, activeUsers: 1 },
          ],
          topTags: [{ tag: "typescript", count: 6 }],
        }),
      );

    const page = await adminPageHtml("/admin");
    const host = document.createElement("div");
    host.innerHTML = page.html;

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/admin/stats",
      expect.objectContaining({ credentials: "include" }),
    );
    expect(host.querySelectorAll(".sv-stat-value")).toHaveLength(5);
    expect(host.textContent).toContain("12");
    expect(host.querySelectorAll(".sv-chart-bar")).toHaveLength(4);
    expect(
      host.querySelector('[data-tag="typescript"]')?.hasAttribute("disabled"),
    ).toBe(true);
    expect(host.querySelector('a[href="/admin/reports"]')).not.toBeNull();
    expect(host.querySelector('a[href="/admin/users"]')).toBeNull();
    expect(host.querySelector('a[href="/admin/audit"]')).toBeNull();
    expect(host.querySelector('a[href="/admin/messages"]')).toBeNull();
  });

  it("renders every moderation disposition and masks unavailable bookmark context", async () => {
    adminSession();
    history.replaceState(null, "", "/admin/reports");
    state.adminReports.status = "actioned";
    state.adminReports.page = 2;

    const reports = [
      {
        id: "report-open",
        bookmarkId: "bookmark-visible",
        reporter: "alice",
        reason: "spam" as const,
        comment: "duplicate links",
        status: "open" as const,
        createdAt: "2026-07-11T09:00:00Z",
      },
      {
        id: "report-actioned",
        bookmarkId: "bookmark-missing",
        reporter: "bob",
        reason: "offensive" as const,
        status: "actioned" as const,
        createdAt: "2026-07-11T10:00:00Z",
      },
      {
        id: "report-dismissed",
        bookmarkId: "bookmark-visible",
        reporter: "carol",
        reason: "broken-link" as const,
        status: "dismissed" as const,
        createdAt: "2026-07-11T11:00:00Z",
      },
    ];
    const requested: URL[] = [];
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const url = new URL(String(input), window.location.origin);
      requested.push(url);
      if (url.pathname === "/api/v1/admin/reports") {
        return jsonResponse(paged(reports, 4));
      }
      if (url.pathname === "/api/v1/bookmarks/bookmark-visible") {
        return jsonResponse({
          id: "bookmark-visible",
          url: "https://example.test/visible",
          title: "Visible bookmark",
          tags: [],
          visibility: "public",
          status: "active",
          owner: "owner",
          createdAt: "2026-07-10T09:00:00Z",
          updatedAt: "2026-07-10T09:00:00Z",
        });
      }
      return jsonResponse(
        { title: "Not found", status: 404, detail: "Not found" },
        404,
      );
    });

    const page = await adminPageHtml("/admin/reports");
    page.publish?.();
    const host = document.createElement("div");
    host.innerHTML = page.html;

    const listRequest = requested.find(
      (url) => url.pathname === "/api/v1/admin/reports",
    );
    expect(listRequest?.searchParams.get("status")).toBe("actioned");
    expect(listRequest?.searchParams.get("page")).toBe("2");
    expect(state.adminReports.items).toEqual(reports);
    expect(host.textContent).toContain("Visible bookmark");
    expect(host.textContent).toContain("bookmark-missing");

    const openRow = host.querySelector('[data-ctx="report:report-open"]');
    expect(
      openRow?.querySelectorAll('[data-action="resolve-report"]'),
    ).toHaveLength(2);
    expect(
      Array.from(
        openRow?.querySelectorAll<HTMLElement>("[data-resolution]") ?? [],
      ).map((button) => button.dataset.resolution),
    ).toEqual(["dismissed", "actioned"]);

    const actionedRow = host.querySelector(
      '[data-ctx="report:report-actioned"]',
    );
    expect(actionedRow?.querySelector('[data-resolution="dismissed"]')).not.toBeNull();
    expect(actionedRow?.querySelector('[data-resolution="open"]')).not.toBeNull();

    const dismissedRow = host.querySelector(
      '[data-ctx="report:report-dismissed"]',
    );
    expect(dismissedRow?.querySelector('[data-resolution="actioned"]')).not.toBeNull();
    expect(dismissedRow?.querySelector('[data-resolution="open"]')).not.toBeNull();
  });

  it("applies admin list filters and publishes only action-target records", async () => {
    adminSession();
    state.users.q = "ali & bob";
    state.users.page = 2;
    state.audit.actor = "moderator";
    state.audit.action = "report.resolved";
    state.audit.from = "2026-07-01";
    state.audit.to = "2026-07-11";
    state.audit.page = 3;
    state.messages.q = "validation";
    state.messages.language = "pl";
    state.messages.page = 4;

    const users = [
      {
        username: "admin",
        firstSeen: "2026-07-01T00:00:00Z",
        lastSeen: "2026-07-11T08:00:00Z",
        status: "active" as const,
        bookmarkCount: 2,
      },
      {
        username: "alice",
        firstSeen: "2026-07-01T00:00:00Z",
        lastSeen: "2026-07-11T08:30:00Z",
        status: "active" as const,
        bookmarkCount: 3,
      },
      {
        username: "bob",
        firstSeen: "2026-07-01T00:00:00Z",
        lastSeen: "2026-07-11T09:00:00Z",
        status: "blocked" as const,
        blockedReason: "abuse <script>",
        bookmarkCount: 0,
      },
    ];
    const messages = [
      {
        id: "message-1",
        key: "validation.url.invalid",
        language: "pl",
        text: "Nieprawidlowy <adres>",
        description: "URL validation",
        createdAt: "2026-07-01T00:00:00Z",
        updatedAt: "2026-07-02T00:00:00Z",
      },
    ];
    const requests: URL[] = [];
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const url = new URL(String(input), window.location.origin);
      requests.push(url);
      if (url.pathname === "/api/v1/admin/users") {
        return jsonResponse(paged(users, 5));
      }
      if (url.pathname === "/api/v1/admin/audit-log") {
        return jsonResponse(
          paged([
            {
              id: "audit-1",
              actor: "moderator",
              action: "report.resolved",
              targetType: "report",
              targetId: "report-1<script>",
              createdAt: "2026-07-11T10:00:00Z",
            },
          ]),
        );
      }
      if (url.pathname === "/api/v1/messages") {
        return jsonResponse(paged(messages, 6));
      }
      return new Response(null, { status: 404 });
    });

    history.replaceState(null, "", "/admin/users");
    const usersPage = await adminPageHtml("/admin/users");
    usersPage.publish?.();
    const usersHost = document.createElement("div");
    usersHost.innerHTML = usersPage.html;
    const usersRequest = requests.find(
      (url) => url.pathname === "/api/v1/admin/users",
    );
    expect(usersRequest?.searchParams.get("q")).toBe("ali & bob");
    expect(usersRequest?.searchParams.get("page")).toBe("2");
    expect(state.users.items).toEqual(users);
    expect(
      usersHost.querySelector('[data-ctx="user:admin"] [data-action]'),
    ).toBeNull();
    expect(
      usersHost.querySelector(
        '[data-ctx="user:alice"] [data-action="open-block-user"]',
      ),
    ).not.toBeNull();
    expect(
      usersHost.querySelector(
        '[data-ctx="user:bob"] [data-action="unblock-user"]',
      ),
    ).not.toBeNull();
    expect(usersPage.html).not.toContain("<script>");

    history.replaceState(null, "", "/admin/audit");
    const auditPage = await adminPageHtml("/admin/audit");
    const auditRequest = requests.find(
      (url) => url.pathname === "/api/v1/admin/audit-log",
    );
    expect(auditRequest?.searchParams.get("actor")).toBe("moderator");
    expect(auditRequest?.searchParams.get("action")).toBe("report.resolved");
    expect(auditRequest?.searchParams.get("from")).toBe(
      new Date("2026-07-01T00:00:00").toISOString(),
    );
    expect(auditRequest?.searchParams.get("to")).toBe(
      new Date("2026-07-11T23:59:59.999")
        .toISOString()
        .replace(".999Z", ".999999Z"),
    );
    expect(auditRequest?.searchParams.get("page")).toBe("3");
    expect(auditPage.html).toContain("report-1&lt;script&gt;");

    history.replaceState(null, "", "/admin/messages");
    const messagesPage = await adminPageHtml("/admin/messages");
    messagesPage.publish?.();
    const messagesRequest = requests.find(
      (url) => url.pathname === "/api/v1/messages",
    );
    expect(messagesRequest?.searchParams.get("q")).toBe("validation");
    expect(messagesRequest?.searchParams.get("language")).toBe("pl");
    expect(messagesRequest?.searchParams.get("page")).toBe("4");
    expect(state.messages.items).toEqual(messages);
    expect(messagesPage.html).toContain("Nieprawidlowy &lt;adres&gt;");
    expect(messagesPage.html).toContain('data-action="open-message-create"');
    expect(messagesPage.html).toContain('data-action="open-message-edit"');
    expect(messagesPage.html).toContain('data-action="open-message-delete"');
  });
});

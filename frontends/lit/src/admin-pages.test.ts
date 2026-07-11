import { apiGet } from "./api";
import {
  adminPageHtml,
  auditPageHtml,
  dailyChartHtml,
  endOfDayIso,
  messagesPageHtml,
  usersPageHtml,
} from "./admin-pages";
import { state } from "./app-state";

vi.mock("./api", async (importOriginal) => {
  const original = await importOriginal<typeof import("./api")>();
  return { ...original, apiGet: vi.fn() };
});

const mockedApiGet = vi.mocked(apiGet);

beforeEach(() => {
  mockedApiGet.mockReset();
  state.session = { authenticated: false };
  state.me = null;
  state.users = { q: "", page: 0, items: [] };
  state.audit = { actor: "", action: "", from: "", to: "", page: 0 };
  state.messages = { q: "", language: "", page: 0, items: [] };
  history.replaceState(null, "", "/admin");
});

describe("admin authorization and moderation views", () => {
  it("requires a session, rejects regular users, and limits moderator navigation", async () => {
    expect(await adminPageHtml("/admin")).toContain("/auth/login");

    state.session = { authenticated: true, username: "demo" };
    state.me = { username: "demo", roles: [] };
    expect(await adminPageHtml("/admin")).toContain(">403<");

    state.me = { username: "moderator", roles: ["moderator"] };
    mockedApiGet.mockResolvedValue({
      totals: {
        users: 1,
        bookmarks: 2,
        publicBookmarks: 1,
        hiddenBookmarks: 0,
        openReports: 0,
      },
      daily: [],
      topTags: [],
    });
    const moderatorHtml = await adminPageHtml("/admin");
    expect(moderatorHtml).toContain("/admin/reports");
    expect(moderatorHtml).not.toContain("/admin/users");
    expect(moderatorHtml).not.toContain("/admin/audit");
    expect(moderatorHtml).not.toContain("/admin/messages");
  });

  it("renders admin-only navigation for the hierarchical admin role", async () => {
    state.session = { authenticated: true, username: "admin" };
    state.me = { username: "admin", roles: ["admin"] };
    mockedApiGet.mockResolvedValue({
      items: [],
      page: 0,
      size: 20,
      totalItems: 0,
      totalPages: 0,
    });

    const html = await adminPageHtml("/admin/messages");
    expect(html).toContain("/admin/users");
    expect(html).toContain("/admin/audit");
    expect(html).toContain("/admin/messages");
  });

  it("renders moderation statistics with safe labels and zero-safe chart scaling", () => {
    const html = dailyChartHtml([
      { date: "2026-07-10<script>", bookmarksCreated: 0, activeUsers: 0 },
      { date: "2026-07-11", bookmarksCreated: 4, activeUsers: 2 },
    ]);
    expect(html).toContain("<svg");
    expect(html).toContain("2026-07-10&lt;script&gt;");
    expect(html).not.toContain("2026-07-10<script>");
    expect(html).toContain(">4</text>");
    expect(dailyChartHtml([])).toContain(">1</text>");
  });

  it("keeps self-block unavailable while exposing block and unblock transitions", async () => {
    state.me = { username: "admin", roles: ["admin"] };
    state.users.q = "a&b";
    state.users.page = 2;
    mockedApiGet.mockResolvedValue({
      items: [
        {
          username: "admin",
          firstSeen: "2026-01-01T00:00:00Z",
          lastSeen: "2026-07-11T00:00:00Z",
          status: "active",
          bookmarkCount: 1,
        },
        {
          username: "alice<script>",
          firstSeen: "2026-01-01T00:00:00Z",
          lastSeen: "2026-07-11T00:00:00Z",
          status: "active",
          bookmarkCount: 2,
        },
        {
          username: "blocked",
          firstSeen: "2026-01-01T00:00:00Z",
          lastSeen: "2026-07-11T00:00:00Z",
          status: "blocked",
          blockedReason: "abuse <x>",
          bookmarkCount: 0,
        },
      ],
      page: 2,
      size: 20,
      totalItems: 41,
      totalPages: 3,
    });

    const html = await usersPageHtml();
    expect(mockedApiGet).toHaveBeenCalledWith("/api/v1/admin/users", {
      q: "a&b",
      page: 2,
    });
    expect(html).not.toMatch(
      /data-ctx="user:admin"[\s\S]*?data-username="admin"/,
    );
    expect(html).toContain('data-username="alice&lt;script&gt;"');
    expect(html).toContain('data-action="unblock-user"');
    expect(html).toContain('title="abuse &lt;x&gt;"');
  });

  it("passes inclusive audit boundaries and escapes audit detail", async () => {
    state.audit = {
      actor: "alice",
      action: "user.blocked",
      from: "2026-07-01",
      to: "2026-07-11",
      page: 1,
    };
    mockedApiGet.mockResolvedValue({
      items: [
        {
          id: "audit-1",
          actor: "alice<script>",
          action: "user.blocked",
          targetType: "user",
          targetId: "bob&co",
          createdAt: "2026-07-11T10:00:00Z",
        },
      ],
      page: 1,
      size: 20,
      totalItems: 21,
      totalPages: 2,
    });

    const html = await auditPageHtml();
    expect(mockedApiGet).toHaveBeenCalledWith(
      "/api/v1/admin/audit-log",
      expect.objectContaining({
        actor: "alice",
        action: "user.blocked",
        page: 1,
        from: new Date("2026-07-01T00:00:00").toISOString(),
        to: endOfDayIso("2026-07-11"),
      }),
    );
    expect(endOfDayIso("2026-07-11")).toMatch(
      /T21:59:59\.999999Z$|T22:59:59\.999999Z$|T23:59:59\.999999Z$/,
    );
    expect(html).toContain("alice&lt;script&gt;");
    expect(html).toContain("user:bob&amp;co");
  });

  it("filters messages and escapes runtime-managed text before rendering actions", async () => {
    state.messages = { q: "login", language: "pl", page: 1, items: [] };
    mockedApiGet.mockResolvedValue({
      items: [
        {
          id: "message-1",
          key: "ui.login<title>",
          language: "pl",
          text: "Witaj <script>",
          description: "",
          createdAt: "2026-01-01T00:00:00Z",
          updatedAt: "2026-01-01T00:00:00Z",
        },
      ],
      page: 1,
      size: 20,
      totalItems: 21,
      totalPages: 2,
    });

    const html = await messagesPageHtml();
    expect(mockedApiGet).toHaveBeenCalledWith("/api/v1/messages", {
      q: "login",
      language: "pl",
      page: 1,
    });
    expect(html).toContain("ui.login&lt;title&gt;");
    expect(html).toContain("Witaj &lt;script&gt;");
    expect(html).toContain('data-action="open-message-edit"');
    expect(html).toContain('data-action="open-message-delete"');
  });
});

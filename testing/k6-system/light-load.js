import { check, group, sleep } from "k6";
import {
  createBookmark,
  deleteBookmark,
  ensureSession,
  getJson,
  loginAs,
  logout,
  numericEnv,
  parseJson,
  standardThresholds,
  uniqueRunId,
} from "./lib/stackverse.js";

const DURATION = __ENV.K6_DURATION || "30s";
const PUBLIC_VUS = numericEnv("K6_PUBLIC_VUS", 1);
const AUTH_VUS = numericEnv("K6_AUTH_VUS", 1);

let demoSession = { value: null };

export const options = {
  scenarios: {
    public_feed: {
      executor: "constant-vus",
      exec: "publicTraffic",
      vus: PUBLIC_VUS,
      duration: DURATION,
      gracefulStop: "5s",
    },
    authenticated_reads: {
      executor: "constant-vus",
      exec: "authenticatedTraffic",
      vus: AUTH_VUS,
      duration: DURATION,
      gracefulStop: "5s",
    },
  },
  thresholds: standardThresholds("steady"),
};

export function setup() {
  const runId = uniqueRunId("k6-load");
  loginAs("demo", "setup");
  const bookmark = createBookmark(
    {
      url: `https://example.com/stackverse/k6/${runId}`,
      title: `k6 load fixture ${runId}`,
      notes: "Read-only fixture for the Stackverse k6 light-load suite.",
      tags: ["k6-load"],
      visibility: "public",
    },
    "setup",
  );
  logout("setup");

  return {
    bookmarkId: bookmark.id,
    title: bookmark.title,
  };
}

export function publicTraffic() {
  group("public traffic", () => {
    const feed = getJson("/api/v2/bookmarks?visibility=public&size=10", 200, {
      traffic: "steady",
      flow: "public-feed",
    });
    check(feed, {
      "public feed returns an item array": (r) => Array.isArray(parseJson(r, "public feed").items),
    });

    const messages = getJson("/api/v1/messages/bundle?lang=en", 200, {
      traffic: "steady",
      flow: "message-bundle",
    });
    check(messages, {
      "message bundle returns messages": (r) => typeof parseJson(r, "message bundle").messages === "object",
    });

    const session = getJson("/auth/session", 200, {
      traffic: "steady",
      flow: "anonymous-session",
    });
    check(session, {
      "anonymous load VU stays logged out": (r) => parseJson(r, "anonymous session").authenticated === false,
    });
  });

  sleep(1);
}

export function authenticatedTraffic(data) {
  ensureSession(demoSession, "demo", "login");

  group("authenticated user reads", () => {
    const me = getJson("/api/v1/me", 200, { traffic: "steady", flow: "me", role: "demo" });
    check(me, {
      "demo identity remains authenticated": (r) => parseJson(r, "me").username === "demo",
    });

    const bookmarks = getJson("/api/v2/bookmarks?size=10", 200, {
      traffic: "steady",
      flow: "my-bookmarks",
      role: "demo",
    });
    check(bookmarks, {
      "bookmark listing returns items": (r) => Array.isArray(parseJson(r, "my bookmarks").items),
    });

    const fixture = getJson(`/api/v1/bookmarks/${encodeURIComponent(data.bookmarkId)}`, 200, {
      traffic: "steady",
      flow: "bookmark-read",
      role: "demo",
    });
    check(fixture, {
      "load fixture remains readable": (r) => parseJson(r, "load fixture").title === data.title,
    });

    const tags = getJson("/api/v1/tags", 200, { traffic: "steady", flow: "tags", role: "demo" });
    check(tags, {
      "tag listing returns tags": (r) => Array.isArray(parseJson(r, "tags").tags),
    });
  });

  sleep(1);
}

export function teardown(data) {
  if (!data || !data.bookmarkId) {
    return;
  }

  loginAs("demo", "teardown");
  deleteBookmark(data.bookmarkId, "teardown");
  logout("teardown");
}

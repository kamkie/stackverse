import { check, group } from "k6";
import {
  createBookmark,
  deleteBookmark,
  getJson,
  loginAs,
  logout,
  parseJson,
  standardThresholds,
  uniqueRunId,
} from "./lib/stackverse.js";

export const options = {
  scenarios: {
    smoke: {
      executor: "shared-iterations",
      vus: 1,
      iterations: 1,
      maxDuration: "2m",
    },
  },
  thresholds: standardThresholds("smoke"),
};

export default function () {
  const runId = uniqueRunId("k6-smoke");
  let bookmarkId;

  group("anonymous public surface", () => {
    const session = getJson("/auth/session", 200, { traffic: "smoke", flow: "anonymous-session" });
    check(session, {
      "anonymous session is reported as logged out": (r) => parseJson(r, "anonymous session").authenticated === false,
    });

    const feed = getJson("/api/v2/bookmarks?visibility=public&size=5", 200, {
      traffic: "smoke",
      flow: "public-feed",
    });
    check(feed, {
      "public feed returns an item array": (r) => Array.isArray(parseJson(r, "public feed").items),
    });

    const bundle = getJson("/api/v1/messages/bundle?lang=en", 200, {
      traffic: "smoke",
      flow: "message-bundle",
    });
    check(bundle, {
      "message bundle includes messages": (r) => typeof parseJson(r, "message bundle").messages === "object",
    });
  });

  group("authenticated bookmark workflow", () => {
    loginAs("demo", "smoke");

    const me = getJson("/api/v1/me", 200, { traffic: "smoke", flow: "me", role: "demo" });
    check(me, {
      "demo identity is returned": (r) => parseJson(r, "me").username === "demo",
    });

    const bookmark = createBookmark(
      {
        url: `https://example.com/stackverse/k6/${runId}`,
        title: `k6 smoke ${runId}`,
        notes: "Created and deleted by the Stackverse k6 smoke suite.",
        tags: ["k6-smoke"],
        visibility: "public",
      },
      "smoke",
    );
    bookmarkId = bookmark.id;

    const readBack = getJson(`/api/v1/bookmarks/${encodeURIComponent(bookmarkId)}`, 200, {
      traffic: "smoke",
      flow: "bookmark-read",
      role: "demo",
    });
    check(readBack, {
      "created bookmark can be read": (r) => parseJson(r, "bookmark read").title === `k6 smoke ${runId}`,
    });

    const tags = getJson("/api/v1/tags", 200, { traffic: "smoke", flow: "tags", role: "demo" });
    check(tags, {
      "tag listing returns tags": (r) => Array.isArray(parseJson(r, "tags").tags),
    });
  });

  logout("smoke");

  group("moderator read workflow", () => {
    loginAs("moderator", "smoke");

    const stats = getJson("/api/v1/admin/stats", 200, {
      traffic: "smoke",
      flow: "admin-stats",
      role: "moderator",
    });
    check(stats, {
      "moderator can read stats": (r) => typeof parseJson(r, "admin stats").totals === "object",
    });

    const reports = getJson("/api/v1/admin/reports?status=open&size=5", 200, {
      traffic: "smoke",
      flow: "admin-reports",
      role: "moderator",
    });
    check(reports, {
      "moderator can read report queue": (r) => Array.isArray(parseJson(r, "admin reports").items),
    });
  });

  logout("smoke");

  if (bookmarkId) {
    group("cleanup", () => {
      loginAs("demo", "smoke");
      deleteBookmark(bookmarkId, "smoke");
      logout("smoke");
    });
  }
}

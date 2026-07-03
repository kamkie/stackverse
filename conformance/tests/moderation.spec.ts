// The moderation workflow (SPEC rules 13–15): report constraints, the report
// state machine with sibling auto-resolution, and hidden-bookmark behavior.
import {
  createBookmark,
  expect,
  expectProblem,
  test,
  uid,
  type Bookmark,
  type Report,
} from "./fixtures";

test("reporting is constrained to visible public bookmarks", async ({ demo, mentor }) => {
  const priv = await createBookmark(demo, {
    url: "https://example.com/report-private",
    title: `report private ${uid()}`,
    visibility: "private",
  });
  const pub = await createBookmark(demo, {
    url: "https://example.com/report-public",
    title: `report public ${uid()}`,
    visibility: "public",
  });
  // private → 404 masking (and masking may precede validation), invalid
  // reason on a visible bookmark → 400, unknown id → 404
  await expectProblem(
    await mentor.post(`/api/v1/bookmarks/${priv.id}/reports`, { data: { reason: "spam" } }),
    404,
  );
  await expectProblem(
    await mentor.post(`/api/v1/bookmarks/${pub.id}/reports`, { data: { reason: "dunno" } }),
    400,
  );
  await expectProblem(
    await mentor.post("/api/v1/bookmarks/00000000-0000-0000-0000-000000000000/reports", {
      data: { reason: "spam" },
    }),
    404,
  );
});

test("dismissal resolves the report and leaves the bookmark alone", async ({ demo, mentor, moderator }) => {
  const bookmark = await createBookmark(demo, {
    url: "https://example.com/dismiss",
    title: `dismiss ${uid()}`,
    visibility: "public",
  });

  const reported = await mentor.post(`/api/v1/bookmarks/${bookmark.id}/reports`, {
    data: { reason: "spam", comment: "conformance dismiss flow" },
  });
  expect(reported.status(), await reported.text()).toBe(201);
  const report = (await reported.json()) as Report;
  expect(report.status).toBe("open");
  expect(report.reporter).toBe("mentor");

  // at most one open report per (bookmark, reporter)
  await expectProblem(
    await mentor.post(`/api/v1/bookmarks/${bookmark.id}/reports`, { data: { reason: "other" } }),
    409,
  );

  // resolution needs the moderator role
  await expectProblem(
    await demo.put(`/api/v1/admin/reports/${report.id}`, { data: { resolution: "dismissed" } }),
    403,
  );

  const resolved = await moderator.put(`/api/v1/admin/reports/${report.id}`, {
    data: { resolution: "dismissed", note: "not spam" },
  });
  expect(resolved.status(), await resolved.text()).toBe(200);
  const dismissed = (await resolved.json()) as Report;
  expect(dismissed.status).toBe("dismissed");
  expect(dismissed.resolvedBy).toBe("moderator");
  expect(dismissed.resolutionNote).toBe("not spam");
  expect(Date.parse(dismissed.resolvedAt ?? "")).not.toBeNaN();

  // dismissal has no effect on the bookmark…
  const after = (await (await demo.get(`/api/v1/bookmarks/${bookmark.id}`)).json()) as Bookmark;
  expect(after.status).toBe("active");
  // …and the reporter may report again once resolved
  const again = await mentor.post(`/api/v1/bookmarks/${bookmark.id}/reports`, {
    data: { reason: "spam" },
  });
  expect(again.status(), await again.text()).toBe(201);
  const cleanup = (await again.json()) as Report;
  await moderator.put(`/api/v1/admin/reports/${cleanup.id}`, { data: { resolution: "dismissed" } });
});

test("actioning hides the bookmark and auto-resolves sibling reports", async ({ demo, mentor, moderator, admin, anon }) => {
  const tag = `hide-${uid()}`;
  const bookmark = await createBookmark(demo, {
    url: "https://example.com/actioned",
    title: `actioned ${tag}`,
    tags: [tag],
    visibility: "public",
  });

  const first = (await (
    await mentor.post(`/api/v1/bookmarks/${bookmark.id}/reports`, { data: { reason: "offensive" } })
  ).json()) as Report;
  const sibling = (await (
    await moderator.post(`/api/v1/bookmarks/${bookmark.id}/reports`, { data: { reason: "spam" } })
  ).json()) as Report;
  expect(sibling.status).toBe("open");

  const note = `actioned by conformance ${tag}`;
  const resolved = await moderator.put(`/api/v1/admin/reports/${first.id}`, {
    data: { resolution: "actioned", note },
  });
  expect(resolved.status(), await resolved.text()).toBe(200);

  // the bookmark is hidden: owner still sees it, others and the feed do not
  const mine = (await (await demo.get(`/api/v1/bookmarks/${bookmark.id}`)).json()) as Bookmark;
  expect(mine.status).toBe("hidden");
  await expectProblem(await anon.get(`/api/v1/bookmarks/${bookmark.id}`), 404);
  const feed = (await (
    await anon.get(`/api/v1/bookmarks?visibility=public&tag=${tag}`)
  ).json()) as { items: Bookmark[] };
  expect(feed.items).toHaveLength(0);

  // hidden bookmarks cannot be reported or re-published by the owner
  await expectProblem(
    await mentor.post(`/api/v1/bookmarks/${bookmark.id}/reports`, { data: { reason: "spam" } }),
    404,
  );
  await expectProblem(
    await demo.put(`/api/v1/bookmarks/${bookmark.id}`, {
      data: { url: bookmark.url, title: bookmark.title, visibility: "public" },
    }),
    409,
  );

  // the sibling was auto-actioned with the same resolver and note — page
  // through the whole actioned queue, long-lived databases hold many pages
  let autoResolved: Report | undefined;
  for (let page = 0; autoResolved === undefined; page++) {
    const queue = (await (
      await admin.get(`/api/v1/admin/reports?status=actioned&size=100&page=${page}`)
    ).json()) as { items: Report[]; totalPages: number };
    autoResolved = queue.items.find((item) => item.id === sibling.id);
    if (page >= queue.totalPages - 1) break;
  }
  expect(autoResolved, "sibling report must be auto-resolved").toBeDefined();
  expect(autoResolved?.status).toBe("actioned");
  expect(autoResolved?.resolvedBy).toBe("moderator");
  expect(autoResolved?.resolutionNote).toBe(note);

  // the state machine only resolves open reports
  await expectProblem(
    await moderator.put(`/api/v1/admin/reports/${first.id}`, { data: { resolution: "dismissed" } }),
    409,
  );

  // restore: status back to active, visibility untouched
  const restored = await moderator.put(`/api/v1/admin/bookmarks/${bookmark.id}/status`, {
    data: { status: "active", note: "restored by conformance" },
  });
  expect(restored.status(), await restored.text()).toBe(200);
  const back = (await restored.json()) as Bookmark;
  expect(back.status).toBe("active");
  expect(back.visibility).toBe("public");
  expect((await anon.get(`/api/v1/bookmarks/${bookmark.id}`)).status()).toBe(200);
});

test("the moderation queue defaults to open reports, oldest first", async ({ demo, mentor, moderator }) => {
  const bookmark = await createBookmark(demo, {
    url: "https://example.com/queue",
    title: `queue ${uid()}`,
    visibility: "public",
  });
  const mine = (await (
    await mentor.post(`/api/v1/bookmarks/${bookmark.id}/reports`, { data: { reason: "other" } })
  ).json()) as Report;

  const response = await moderator.get("/api/v1/admin/reports?size=100");
  expect(response.status()).toBe(200);
  const queue = (await response.json()) as { items: Report[] };
  expect(queue.items.map((item) => item.id)).toContain(mine.id);
  for (const item of queue.items) expect(item.status).toBe("open");
  const created = queue.items.map((item) => Date.parse(item.createdAt));
  expect([...created].sort((a, b) => a - b)).toEqual(created);

  await moderator.put(`/api/v1/admin/reports/${mine.id}`, { data: { resolution: "dismissed" } });
});

test("reporters see their own reports, newest first, with a status filter", async ({ demo, mentor, moderator }) => {
  const first = await createBookmark(demo, {
    url: "https://example.com/my-reports-1",
    title: `my reports first ${uid()}`,
    visibility: "public",
  });
  const second = await createBookmark(demo, {
    url: "https://example.com/my-reports-2",
    title: `my reports second ${uid()}`,
    visibility: "public",
  });
  const ra = (await (await mentor.post(`/api/v1/bookmarks/${first.id}/reports`, {
    data: { reason: "spam" },
  })).json()) as Report;
  const rb = (await (await mentor.post(`/api/v1/bookmarks/${second.id}/reports`, {
    data: { reason: "other" },
  })).json()) as Report;
  const foreign = (await (await moderator.post(`/api/v1/bookmarks/${first.id}/reports`, {
    data: { reason: "spam" },
  })).json()) as Report;

  const mine = async (query = "") => {
    const response = await mentor.get(`/api/v1/reports?size=100${query}`);
    expect(response.status(), await response.text()).toBe(200);
    return ((await response.json()) as { items: Report[] }).items;
  };

  // only the caller's reports, newest first
  const items = await mine();
  expect(items.every((r) => r.reporter === "mentor")).toBe(true);
  const ids = items.map((r) => r.id);
  expect(ids).toContain(ra.id);
  expect(ids).toContain(rb.id);
  expect(ids).not.toContain(foreign.id);
  expect(ids.indexOf(rb.id)).toBeLessThan(ids.indexOf(ra.id));

  // the status filter reflects moderation's disposition — the feedback loop
  await moderator.put(`/api/v1/admin/reports/${ra.id}`, {
    data: { resolution: "dismissed", note: "conformance feedback" },
  });
  const open = await mine("&status=open");
  expect(open.map((r) => r.id)).toContain(rb.id);
  expect(open.map((r) => r.id)).not.toContain(ra.id);
  const dismissed = (await mine("&status=dismissed")).find((r) => r.id === ra.id);
  expect(dismissed?.resolvedBy).toBe("moderator");
  expect(dismissed?.resolutionNote).toBe("conformance feedback");

  // cleanup: don't leave open reports behind for other tests or reruns
  await mentor.delete(`/api/v1/reports/${rb.id}`);
  await moderator.put(`/api/v1/admin/reports/${foreign.id}`, { data: { resolution: "dismissed" } });
});

test("a reporter may revise their own open report; everyone else gets a 404 mask", async ({ demo, mentor, moderator, anon }) => {
  const bookmark = await createBookmark(demo, {
    url: "https://example.com/revise",
    title: `revise ${uid()}`,
    visibility: "public",
  });
  const report = (await (await mentor.post(`/api/v1/bookmarks/${bookmark.id}/reports`, {
    data: { reason: "spam" },
  })).json()) as Report;

  const revised = await mentor.put(`/api/v1/reports/${report.id}`, {
    data: { reason: "other", comment: "changed my mind" },
  });
  expect(revised.status(), await revised.text()).toBe(200);
  const body = (await revised.json()) as Report;
  expect(body.reason).toBe("other");
  expect(body.comment).toBe("changed my mind");
  expect(body.status).toBe("open");

  // not the reporter → 404 (even with the moderator role); anonymous → 401
  await expectProblem(
    await moderator.put(`/api/v1/reports/${report.id}`, { data: { reason: "spam" } }),
    404,
  );
  await expectProblem(
    await anon.put(`/api/v1/reports/${report.id}`, { data: { reason: "spam" } }),
    401,
  );
  // invalid reason → validation problem
  await expectProblem(
    await mentor.put(`/api/v1/reports/${report.id}`, { data: { reason: "dunno" } }),
    400,
  );

  // resolved reports are frozen
  await moderator.put(`/api/v1/admin/reports/${report.id}`, { data: { resolution: "dismissed" } });
  await expectProblem(
    await mentor.put(`/api/v1/reports/${report.id}`, { data: { reason: "spam" } }),
    409,
  );
});

test("withdrawing an open report removes it and frees the one-open-report slot", async ({ demo, mentor, moderator }) => {
  const bookmark = await createBookmark(demo, {
    url: "https://example.com/withdraw",
    title: `withdraw ${uid()}`,
    visibility: "public",
  });
  const report = (await (await mentor.post(`/api/v1/bookmarks/${bookmark.id}/reports`, {
    data: { reason: "spam" },
  })).json()) as Report;

  // not the reporter → 404 mask
  await expectProblem(await moderator.delete(`/api/v1/reports/${report.id}`), 404);

  expect((await mentor.delete(`/api/v1/reports/${report.id}`)).status()).toBe(204);

  // gone from the reporter's list and from the moderation queue
  const mine = ((await (await mentor.get("/api/v1/reports?size=100")).json()) as { items: Report[] }).items;
  expect(mine.map((r) => r.id)).not.toContain(report.id);
  const queue = ((await (await moderator.get("/api/v1/admin/reports?size=100")).json()) as { items: Report[] }).items;
  expect(queue.map((r) => r.id)).not.toContain(report.id);

  // the slot is free again; a resolved report cannot be withdrawn
  const again = await mentor.post(`/api/v1/bookmarks/${bookmark.id}/reports`, { data: { reason: "other" } });
  expect(again.status(), await again.text()).toBe(201);
  const againId = ((await again.json()) as Report).id;
  await moderator.put(`/api/v1/admin/reports/${againId}`, { data: { resolution: "dismissed" } });
  await expectProblem(await mentor.delete(`/api/v1/reports/${againId}`), 409);
});

test("hiding and restoring via the status endpoint is moderator-only and validated", async ({ demo, moderator }) => {
  const bookmark = await createBookmark(demo, {
    url: "https://example.com/direct-hide",
    title: `direct hide ${uid()}`,
    visibility: "public",
  });
  await expectProblem(
    await demo.put(`/api/v1/admin/bookmarks/${bookmark.id}/status`, { data: { status: "hidden" } }),
    403,
  );
  await expectProblem(
    await moderator.put(`/api/v1/admin/bookmarks/${bookmark.id}/status`, { data: { status: "gone" } }),
    400,
  );
  const hidden = await moderator.put(`/api/v1/admin/bookmarks/${bookmark.id}/status`, {
    data: { status: "hidden" },
  });
  expect(hidden.status(), await hidden.text()).toBe(200);
  expect(((await hidden.json()) as Bookmark).status).toBe("hidden");
  await moderator.put(`/api/v1/admin/bookmarks/${bookmark.id}/status`, { data: { status: "active" } });
});

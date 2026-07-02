// Reports queue (frontends/README.md #7): demo reports a public bookmark, the
// moderator dismisses or actions it; actioned reports hide the bookmark from
// the public feed (docs/SPEC.md rule 15).
import { expect, test, type Browser, type Page } from "@playwright/test";
import { apiMutate, authFile, createBookmark, uid } from "./helpers";

test.use({ storageState: authFile("moderator") });

/** Creates a public bookmark as demo and reports it; returns the marker. */
async function reportedBookmark(browser: Browser): Promise<{ marker: string; close: () => Promise<void> }> {
  const context = await browser.newContext({ storageState: authFile("demo") });
  const page = await context.newPage();
  const marker = uid();
  await page.goto("/feed");
  const id = await createBookmark(page, {
    url: `https://example.com/report/${marker}`,
    title: `e2e reported ${marker}`,
    visibility: "public",
  });
  const response = await apiMutate(page, "POST", `/api/v1/bookmarks/${id}/reports`, {
    reason: "spam",
    comment: `e2e report ${marker}`,
  });
  expect(response.status(), await response.text()).toBe(201);
  return { marker, close: () => context.close() };
}

function reportRow(page: Page, marker: string) {
  return page.getByRole("row").filter({ hasText: `e2e report ${marker}` });
}

test("dismissing an open report removes it from the open queue", async ({ page, browser }) => {
  const { marker, close } = await reportedBookmark(browser);
  await close();

  await page.goto("/admin/reports");
  const row = reportRow(page, marker);
  await expect(row).toHaveCount(1);
  await row.getByRole("button", { name: "Dismiss" }).click();
  await expect(row).toHaveCount(0);

  await page.locator(".sv-toolbar .sv-select").selectOption("dismissed");
  await expect(reportRow(page, marker)).toHaveCount(1);
});

test("actioning a report hides the bookmark from the public feed", async ({ page, browser }) => {
  const { marker, close } = await reportedBookmark(browser);
  await close();

  await page.goto("/admin/reports");
  const row = reportRow(page, marker);
  await row.getByRole("button", { name: "Action" }).click();
  await expect(row).toHaveCount(0);

  await page.goto("/feed");
  await expect(
    page.locator(".sv-bookmark").filter({ hasText: `e2e reported ${marker}` }),
  ).toHaveCount(0);
});

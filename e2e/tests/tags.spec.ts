// Tag sidebar (frontends/README.md #4): tags from GET /api/v1/tags, click to
// filter the bookmark list.
import { expect, test } from "@playwright/test";
import { authFile, createBookmark, uid } from "./helpers";

test.use({ storageState: authFile("demo") });

test("clicking a sidebar tag filters the list", async ({ page }) => {
  const marker = uid();
  const tag = `tg-${marker}`;
  await page.goto("/bookmarks");
  await createBookmark(page, {
    url: `https://example.com/tags/${marker}`,
    title: `e2e tagged ${marker}`,
    tags: [tag],
    visibility: "private",
  });
  await page.reload();

  await page
    .locator(".sv-sidebar .sv-tag")
    .filter({ hasText: tag })
    .click();

  await expect(page.locator(".sv-bookmark")).toHaveCount(1);
  await expect(page.locator(".sv-bookmark")).toContainText(`e2e tagged ${marker}`);
});

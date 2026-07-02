// Dashboard (frontends/README.md #6): totals from GET /api/v1/admin/stats and
// the 30-day chart, visible to moderators.
import { expect, test } from "@playwright/test";
import { authFile } from "./helpers";

test.use({ storageState: authFile("moderator") });

test("shows the five stat totals and the daily chart", async ({ page }) => {
  await page.goto("/admin");
  await expect(page.locator(".sv-stat")).toHaveCount(5);
  for (const label of [
    "Users",
    "Bookmarks",
    "Public bookmarks",
    "Hidden bookmarks",
    "Open reports",
  ]) {
    await expect(
      page.locator(".sv-stat-label").filter({ hasText: new RegExp(`^${label}$`) }),
    ).toBeVisible();
  }
  await expect(page.locator("svg.sv-chart")).toBeVisible();
});

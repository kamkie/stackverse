// Dashboard (frontends/README.md #7): totals from GET /api/v1/admin/stats and
// the 30-day chart, visible to moderators.
import { expect, test } from "@playwright/test";
import { authFile } from "./helpers";

test.use({ storageState: authFile("moderator") });

test("shows the five stat totals and the daily chart", async ({ page }) => {
  await page.goto("/admin");
  await expect(page.locator(".sv-stat")).toHaveCount(5);
  for (const label of [
    /^Users$/,
    /^Bookmarks$/,
    /^Public bookmarks$/,
    /^Hidden bookmarks$/,
    /^Open reports?$/, // pluralized with the live count
  ]) {
    await expect(page.locator(".sv-stat-label").filter({ hasText: label })).toBeVisible();
  }
  await expect(page.locator("svg.sv-chart")).toBeVisible();
});

test("the open reports stat links to the reports queue", async ({ page }) => {
  await page.goto("/admin");
  // accessible name is "<count> Open report(s)"
  const stat = page.getByRole("link", { name: /open reports?/i });
  await expect(stat).toHaveAttribute("href", "/admin/reports");
});

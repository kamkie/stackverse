// Language switcher (frontends/README.md #5): en/pl, bundle reloads without a
// page reload, choice persists client-side.
import { expect, test } from "@playwright/test";

test("switching to Polish swaps the runtime bundle and persists", async ({ page }) => {
  await page.goto("/feed");
  await expect(page.locator(".sv-page-title")).toHaveText("Public feed");

  await page.getByRole("button", { name: "PL" }).click();
  await expect(page.locator(".sv-page-title")).toHaveText("Publiczne");
  // anonymous-visible chrome swaps too (My bookmarks is hidden while logged out)
  await expect(
    page.getByRole("navigation").getByRole("link", { name: "Publiczne" }),
  ).toBeVisible();
  await expect(
    page.getByRole("banner").getByRole("link", { name: "Zaloguj się" }),
  ).toBeVisible();

  await page.reload(); // persisted choice applies on the next load
  await expect(page.locator(".sv-page-title")).toHaveText("Publiczne");
});
